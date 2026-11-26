package com.smartstock.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.awaitility.Awaitility.await;

import com.smartstock.contracts.inventory.v1.DeductStockRequest;
import com.smartstock.contracts.inventory.v1.DeductStockResponse;
import com.smartstock.contracts.inventory.v1.InventoryServiceGrpc;
import com.smartstock.contracts.inventory.v1.Product;
import com.smartstock.contracts.sales.v1.CreateSaleRequest;
import com.smartstock.contracts.sales.v1.CreateSaleResponse;
import com.smartstock.contracts.sales.v1.GetSaleRequest;
import com.smartstock.contracts.sales.v1.GetSaleResponse;
import com.smartstock.contracts.sales.v1.RejectionReason;
import com.smartstock.contracts.sales.v1.SaleStatus;
import com.smartstock.contracts.sales.v1.SalesServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"sales.grpc-port=0", "sales.recovery-interval-ms=3600000"})
@Import(SalesIntegrationTest.FakeConfiguration.class)
class SalesIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeInventory inventory;
    @Autowired SalesGrpcService salesService;
    @Autowired SaleCoordinator coordinator;
    @Autowired SaleStore store;
    @Autowired InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;
    private Server server;
    private ManagedChannel channel;
    private SalesServiceGrpc.SalesServiceBlockingStub client;

    @TestConfiguration
    static class FakeConfiguration {
        @Bean FakeInventory fakeInventory() {
            return new FakeInventory();
        }

        @Bean(destroyMethod = "shutdown") Server fakeServer(FakeInventory fake) throws IOException {
            return InProcessServerBuilder.forName("inventory-test").directExecutor().addService(fake).build().start();
        }

        @Bean(destroyMethod = "shutdown") ManagedChannel fakeChannel(Server fakeServer) {
            return InProcessChannelBuilder.forName("inventory-test").directExecutor().build();
        }

        @Bean @Primary InventoryServiceGrpc.InventoryServiceBlockingStub fakeStub(ManagedChannel fakeChannel) {
            return InventoryServiceGrpc.newBlockingStub(fakeChannel);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM sales");
        inventory.reset();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(salesService).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = SalesServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private CreateSaleResponse create(String id, String sku, int quantity) {
        return client.createSale(CreateSaleRequest.newBuilder().setSaleId(id).setSku(sku).setQuantity(quantity).build());
    }

    private GetSaleResponse get(String id) {
        return client.getSale(GetSaleRequest.newBuilder().setSaleId(id).build());
    }

    @Test
    void successRepeatConflictAndLookup() {
        var result = create("sale-1", "SKU", 3);
        assertEquals(CreateSaleResponse.Result.ACCEPTED, result.getResult());
        assertEquals(SaleStatus.CONFIRMED, result.getSale().getStatus());
        assertEquals(7, inventory.stock);
        assertEquals(result.getSale(), create("sale-1", "SKU", 3).getSale());
        assertEquals(1, inventory.deductions);
        assertEquals(CreateSaleResponse.Result.SALE_ID_CONFLICT, create("sale-1", "other", 3).getResult());
        assertEquals(CreateSaleResponse.Result.SALE_ID_CONFLICT, create("sale-1", "SKU", 4).getResult());
        assertEquals(GetSaleResponse.Result.FOUND, get("sale-1").getResult());
        assertEquals(GetSaleResponse.Result.NOT_FOUND, get("missing").getResult());
        assertEquals(GetSaleResponse.Result.INVALID_SALE_ID, get(" ").getResult());
        assertEquals(CreateSaleResponse.Result.INVALID_ARGUMENT, create("", "SKU", 1).getResult());
        assertEquals(CreateSaleResponse.Result.INVALID_ARGUMENT, create("x", " ", 1).getResult());
        assertEquals(CreateSaleResponse.Result.INVALID_ARGUMENT, create("x", "SKU", 0).getResult());
        assertFalse(get("x").hasSale());
        assertEquals(RejectionReason.PRODUCT_NOT_FOUND, create("id\0x", "sku\0x", 1).getSale().getRejectionReason());
        assertEquals("sku\0x", get("id\0x").getSale().getSku());
    }

    @Test
    void definitiveRejectionsStayRejected() {
        var insufficient = create("large", "SKU", 11).getSale();
        assertEquals(SaleStatus.REJECTED, insufficient.getStatus());
        assertEquals(RejectionReason.INSUFFICIENT_STOCK, insufficient.getRejectionReason());
        assertEquals(insufficient, create("large", "SKU", 11).getSale());
        assertEquals(RejectionReason.PRODUCT_NOT_FOUND, create("missing", "unknown", 1).getSale().getRejectionReason());
        assertEquals(10, inventory.stock);
    }

    @Test
    void timeoutBeforeAndAfterDeductionRecoversWithSameId() {
        inventory.failure = Failure.BEFORE;
        assertEquals(SaleStatus.PENDING, create("before", "SKU", 3).getSale().getStatus());
        assertEquals(10, inventory.stock);
        inventory.failure = Failure.NONE;
        coordinator.recover();
        assertEquals(SaleStatus.CONFIRMED, get("before").getSale().getStatus());
        assertEquals(7, inventory.stock);

        inventory.failure = Failure.AFTER;
        assertEquals(SaleStatus.PENDING, create("after", "SKU", 2).getSale().getStatus());
        assertEquals(5, inventory.stock);
        inventory.failure = Failure.NONE;
        // A fresh coordinator reads the durable pending record, as it does on restart.
        new SaleCoordinator(store, inventoryStub, 1000, 100).recover();
        assertEquals(SaleStatus.CONFIRMED, get("after").getSale().getStatus());
        assertEquals(5, inventory.stock);
        assertEquals(2, inventory.deductions);
    }

    @Test
    void concurrentRequestsShareOneOperation() throws Exception {
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Future<CreateSaleResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                futures.add(executor.submit(() -> create("concurrent", "SKU", 3)));
            }
            for (var future : futures) {
                assertEquals(CreateSaleResponse.Result.ACCEPTED, future.get().getResult());
            }
        }
        assertEquals(7, inventory.stock);
        assertEquals(1, inventory.deductions);
        assertEquals(SaleStatus.CONFIRMED, get("concurrent").getSale().getStatus());
    }

    @Test
    void unexpectedInventoryResultsRemainPending() {
        inventory.failure = Failure.UNKNOWN;
        assertEquals(SaleStatus.PENDING, create("unknown", "SKU", 1).getSale().getStatus());
        assertTrue(get("unknown").hasSale());
    }

    @Test
    void recoveryAfterApplicationRestart() throws IOException {
        Server externalInventory = ServerBuilder.forPort(0).addService(inventory).build().start();
        String inventoryTarget = "localhost:" + externalInventory.getPort();
        inventory.failure = Failure.AFTER;
        try {
            try (ConfigurableApplicationContext first = startApplication(inventoryTarget)) {
                int port = first.getBean("grpcServer", Server.class).getPort();
                ManagedChannel firstChannel = io.grpc.ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
                try {
                    var firstClient = SalesServiceGrpc.newBlockingStub(firstChannel);
                    var request = CreateSaleRequest.newBuilder().setSaleId("restart").setSku("SKU").setQuantity(3).build();
                    assertEquals(SaleStatus.PENDING, firstClient.createSale(request).getSale().getStatus());
                    assertEquals(7, inventory.stock);
                } finally {
                    firstChannel.shutdownNow();
                }
            }
            inventory.failure = Failure.NONE;
            try (ConfigurableApplicationContext second = startApplication(inventoryTarget)) {
                SaleStore restartedStore = second.getBean(SaleStore.class);
                await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                        assertEquals(SaleStatus.CONFIRMED, restartedStore.find("restart").orElseThrow().getStatus()));
                assertEquals(7, inventory.stock);
                assertEquals(1, inventory.deductions);
            }
        } finally {
            externalInventory.shutdownNow();
        }
    }

    private ConfigurableApplicationContext startApplication(String inventoryTarget) {
        return new SpringApplicationBuilder(SalesApplication.class).run(
                "--sales.grpc-port=0",
                "--sales.inventory-target=" + inventoryTarget,
                "--sales.recovery-interval-ms=100");
    }

    enum Failure { NONE, BEFORE, AFTER, UNKNOWN }

    static class FakeInventory extends InventoryServiceGrpc.InventoryServiceImplBase {
        volatile int stock;
        volatile int deductions;
        volatile Failure failure;
        final Map<String, DeductStockRequest> requests = new HashMap<>();
        final Map<String, DeductStockResponse> results = new HashMap<>();

        synchronized void reset() {
            stock = 10;
            deductions = 0;
            failure = Failure.NONE;
            requests.clear();
            results.clear();
        }

        @Override
        public synchronized void deductStock(DeductStockRequest request, StreamObserver<DeductStockResponse> observer) {
            if (failure == Failure.BEFORE) {
                observer.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
                return;
            }
            if (failure == Failure.UNKNOWN) {
                observer.onNext(DeductStockResponse.getDefaultInstance());
                observer.onCompleted();
                return;
            }
            DeductStockResponse response = results.get(request.getSaleId());
            if (response != null && !requests.get(request.getSaleId()).equals(request)) {
                response = DeductStockResponse.newBuilder().setResult(DeductStockResponse.Result.SALE_ID_CONFLICT).build();
            } else if (response == null) {
                var builder = DeductStockResponse.newBuilder();
                if (!request.getSku().equals("SKU")) {
                    builder.setResult(DeductStockResponse.Result.PRODUCT_NOT_FOUND);
                } else if (request.getQuantity() > stock) {
                    builder.setResult(DeductStockResponse.Result.INSUFFICIENT_STOCK);
                } else {
                    stock -= request.getQuantity();
                    deductions++;
                    builder.setResult(DeductStockResponse.Result.DEDUCTED)
                            .setProduct(Product.newBuilder().setSku("SKU").setStock(stock));
                }
                response = builder.build();
                requests.put(request.getSaleId(), request);
                results.put(request.getSaleId(), response);
            }
            if (failure == Failure.AFTER) {
                observer.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
            } else {
                observer.onNext(response);
                observer.onCompleted();
            }
        }
    }
}
