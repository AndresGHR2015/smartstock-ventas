package com.smartstock.sales;

import com.smartstock.contracts.inventory.v1.InventoryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcConfiguration {
    @Bean(destroyMethod = "shutdown")
    ManagedChannel inventoryChannel(@Value("${sales.inventory-target}") String target) {
        return ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    }

    @Bean
    InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub(@Qualifier("inventoryChannel") ManagedChannel channel) {
        return InventoryServiceGrpc.newBlockingStub(channel);
    }

    @Bean(destroyMethod = "shutdown")
    Server grpcServer(SalesGrpcService service, @Value("${sales.grpc-port}") int port) throws IOException {
        return ServerBuilder.forPort(port).addService(service).build().start();
    }
}
