package com.smartstock.sales;

import com.smartstock.contracts.sales.v1.CreateSaleRequest;
import com.smartstock.contracts.sales.v1.CreateSaleResponse;
import com.smartstock.contracts.sales.v1.GetSaleRequest;
import com.smartstock.contracts.sales.v1.GetSaleResponse;
import com.smartstock.contracts.sales.v1.GetSalesOverviewRequest;
import com.smartstock.contracts.sales.v1.GetSalesOverviewResponse;
import com.smartstock.contracts.sales.v1.Sale;
import com.smartstock.contracts.sales.v1.SalesServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SalesGrpcService extends SalesServiceGrpc.SalesServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(SalesGrpcService.class);
    private final SaleStore store;
    private final SaleCoordinator coordinator;

    public SalesGrpcService(SaleStore store, SaleCoordinator coordinator) {
        this.store = store;
        this.coordinator = coordinator;
    }

    @Override
    public void createSale(CreateSaleRequest request, StreamObserver<CreateSaleResponse> observer) {
        try {
            if (request.getSaleId().isBlank() || request.getSku().isBlank() || request.getQuantity() <= 0) {
                observer.onNext(CreateSaleResponse.newBuilder().setResult(CreateSaleResponse.Result.INVALID_ARGUMENT).build());
            } else {
                Sale sale = store.createOrGet(request.getSaleId(), request.getSku(), request.getQuantity());
                if (!sale.getSku().equals(request.getSku()) || sale.getQuantity() != request.getQuantity()) {
                    observer.onNext(CreateSaleResponse.newBuilder().setResult(CreateSaleResponse.Result.SALE_ID_CONFLICT).build());
                } else {
                    sale = coordinator.reconcile(sale);
                    observer.onNext(CreateSaleResponse.newBuilder().setResult(CreateSaleResponse.Result.ACCEPTED).setSale(sale).build());
                }
            }
            observer.onCompleted();
        } catch (RuntimeException e) {
            log.error("CreateSale failed", e);
            observer.onError(Status.INTERNAL.withDescription("Sales operation failed").asRuntimeException());
        }
    }

    @Override
    public void getSale(GetSaleRequest request, StreamObserver<GetSaleResponse> observer) {
        try {
            GetSaleResponse.Builder response = GetSaleResponse.newBuilder();
            if (request.getSaleId().isBlank()) {
                response.setResult(GetSaleResponse.Result.INVALID_SALE_ID);
            } else {
                store.find(request.getSaleId()).ifPresentOrElse(
                        sale -> response.setResult(GetSaleResponse.Result.FOUND).setSale(sale),
                        () -> response.setResult(GetSaleResponse.Result.NOT_FOUND));
            }
            observer.onNext(response.build());
            observer.onCompleted();
        } catch (RuntimeException e) {
            log.error("GetSale failed", e);
            observer.onError(Status.INTERNAL.withDescription("Sales lookup failed").asRuntimeException());
        }
    }

    @Override
    public void getSalesOverview(GetSalesOverviewRequest request, StreamObserver<GetSalesOverviewResponse> observer) {
        try {
            observer.onNext(store.overview());
            observer.onCompleted();
        } catch (RuntimeException e) {
            log.error("GetSalesOverview failed", e);
            observer.onError(Status.INTERNAL.withDescription("Sales overview failed").asRuntimeException());
        }
    }
}
