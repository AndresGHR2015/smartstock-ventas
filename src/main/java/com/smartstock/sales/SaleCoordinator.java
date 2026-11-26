package com.smartstock.sales;

import com.smartstock.contracts.inventory.v1.DeductStockRequest;
import com.smartstock.contracts.inventory.v1.DeductStockResponse;
import com.smartstock.contracts.inventory.v1.InventoryServiceGrpc;
import com.smartstock.contracts.sales.v1.RejectionReason;
import com.smartstock.contracts.sales.v1.Sale;
import com.smartstock.contracts.sales.v1.SaleStatus;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SaleCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SaleCoordinator.class);
    private final SaleStore store;
    private final InventoryServiceGrpc.InventoryServiceBlockingStub inventory;
    private final long timeoutMs;
    private final int batchSize;

    public SaleCoordinator(SaleStore store, InventoryServiceGrpc.InventoryServiceBlockingStub inventory,
            @Value("${sales.inventory-timeout-ms}") long timeoutMs,
            @Value("${sales.recovery-batch-size}") int batchSize) {
        this.store = store;
        this.inventory = inventory;
        this.timeoutMs = timeoutMs;
        this.batchSize = batchSize;
    }

    public Sale reconcile(Sale sale) {
        if (sale.getStatus() != SaleStatus.PENDING) {
            return sale;
        }
        store.markAttempt(sale.getSaleId());
        try {
            DeductStockResponse response = inventory.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .deductStock(DeductStockRequest.newBuilder().setSaleId(sale.getSaleId())
                            .setSku(sale.getSku()).setQuantity(sale.getQuantity()).build());
            switch (response.getResult()) {
                case DEDUCTED -> {
                    if (response.hasProduct()) {
                        store.complete(sale.getSaleId(), SaleStatus.CONFIRMED, RejectionReason.REJECTION_REASON_UNSPECIFIED);
                    } else {
                        log.error("Inventory returned DEDUCTED without product for sale {}", sale.getSaleId());
                    }
                }
                case PRODUCT_NOT_FOUND -> store.complete(sale.getSaleId(), SaleStatus.REJECTED, RejectionReason.PRODUCT_NOT_FOUND);
                case INSUFFICIENT_STOCK -> store.complete(sale.getSaleId(), SaleStatus.REJECTED, RejectionReason.INSUFFICIENT_STOCK);
                default -> log.error("Inventory returned unexpected result {} for sale {}", response.getResult(), sale.getSaleId());
            }
        } catch (StatusRuntimeException e) {
            log.warn("Inventory result unknown for sale {}: {}", sale.getSaleId(), e.getStatus());
        }
        return store.find(sale.getSaleId()).orElseThrow();
    }

    @Scheduled(fixedDelayString = "${sales.recovery-interval-ms}")
    public void recover() {
        for (String id : store.pending(batchSize)) {
            try {
                store.find(id).ifPresent(this::reconcile);
            } catch (RuntimeException e) {
                log.error("Recovery failed for sale {}", id, e);
            }
        }
    }
}
