package com.codearena.business.shared.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.codearena.business.internal.grpc.HealthReply;
import com.codearena.business.internal.grpc.HealthRequest;
import com.codearena.business.internal.grpc.LlmHealthGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class HealthControllerGrpcTest {
    @Test
    void reportsCoachAvailabilityFromGrpc() throws Exception {
        Server server = ServerBuilder.forPort(0).addService(new LlmHealthGrpc.LlmHealthImplBase() {
            @Override
            public void check(HealthRequest request, StreamObserver<HealthReply> response) {
                response.onNext(HealthReply.newBuilder().setReady(true).build());
                response.onCompleted();
            }
        }).build().start();
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        HealthController controller = new HealthController(dataSource);
        ReflectionTestUtils.setField(controller, "llmGrpcTarget", "127.0.0.1:" + server.getPort());
        ReflectionTestUtils.setField(controller, "llmBaseUrl", "http://127.0.0.1:8091");
        controller.connectLlmHealth();
        try {
            assertEquals(true, controller.health().get("coach_available"));
            server.shutdownNow();
            server.awaitTermination();
            assertEquals(false, controller.health().get("coach_available"));
        } finally {
            controller.closeLlmHealth();
            server.shutdownNow();
        }
    }
}
