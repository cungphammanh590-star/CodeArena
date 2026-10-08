package com.codearena.business.internal.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.codearena.business.shared.security.InternalTokenGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class InternalGrpcServiceTest {
    private Server server;
    private ManagedChannel channel;
    private InternalBusinessOperations operations;

    @BeforeEach
    void start() throws Exception {
        operations = org.mockito.Mockito.mock(InternalBusinessOperations.class);
        InternalTokenGuard guard = org.mockito.Mockito.mock(InternalTokenGuard.class);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(guard).assertValid(null);
        InternalGrpcService service = new InternalGrpcService(operations, guard, new ObjectMapper());
        server = ServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(service, service.authInterceptor()))
                .build().start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort())
                .usePlaintext().build();
    }

    @AfterEach
    void stop() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    void executesToolWithUserAndParams() throws Exception {
        when(operations.executeTool(eq("u1"), eq("get_latest_submission"),
                eq(Map.of("limit", 1)), eq("s1"), eq(215)))
                .thenReturn(Map.of("ok", true));
        ToolRequest request = ToolRequest.newBuilder()
                .setUserPublicId("u1")
                .setToolName("get_latest_submission")
                .setParamsJson("{\"limit\":1}")
                .setSessionId("s1")
                .setProblemId(215)
                .setHasProblemId(true)
                .build();
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER), "test-token");
        var stub = InternalBusinessGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        JsonReply reply = stub.executeTool(request);
        assertEquals(true, new ObjectMapper().readTree(reply.getJson()).get("ok").asBoolean());
    }

    @Test
    void rejectsCallWithoutToken() {
        var stub = InternalBusinessGrpc.newBlockingStub(channel);
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> stub.getUserLlm(UserRequest.newBuilder().setUserPublicId("u1").build()));
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
    }

    @Test
    void catalogIsAvailableOverGrpc() throws Exception {
        when(operations.listTools()).thenReturn(Map.of("status", "ok", "tools", java.util.List.of()));
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER), "test-token");
        var stub = InternalBusinessGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        JsonReply reply = stub.listTools(UserRequest.getDefaultInstance());
        assertEquals("ok", new ObjectMapper().readTree(reply.getJson()).get("status").asText());
    }
}
