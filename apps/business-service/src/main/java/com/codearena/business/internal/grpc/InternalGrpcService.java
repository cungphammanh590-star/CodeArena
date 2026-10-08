package com.codearena.business.internal.grpc;

import com.codearena.business.shared.security.InternalTokenGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Component
@RequiredArgsConstructor
public class InternalGrpcService extends InternalBusinessGrpc.InternalBusinessImplBase {
    private static final Metadata.Key<String> TOKEN_HEADER =
            Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final InternalBusinessOperations operations;
    private final InternalTokenGuard tokenGuard;
    private final ObjectMapper mapper;

    public ServerInterceptor authInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                String token = headers.get(TOKEN_HEADER);
                try {
                    tokenGuard.assertValid(token);
                } catch (ResponseStatusException ex) {
                    call.close(Status.PERMISSION_DENIED.withDescription("invalid internal token"), new Metadata());
                    return new ServerCall.Listener<>() {};
                }
                return next.startCall(call, headers);
            }
        };
    }

    @Override
    public void listTools(UserRequest request, StreamObserver<JsonReply> response) {
        long start = System.nanoTime();
        try {
            reply(response, operations.listTools());
            log.info("internal grpc catalog ok duration_ms={}", elapsed(start));
        } catch (Exception ex) {
            fail(response, ex, "catalog", "", start);
        }
    }

    @Override
    public void executeTool(ToolRequest request, StreamObserver<JsonReply> response) {
        long start = System.nanoTime();
        try {
            Map<String, Object> result = operations.executeTool(
                    request.getUserPublicId(), request.getToolName(),
                    parseMap(request.getParamsJson()), request.getSessionId(),
                    request.getHasProblemId() ? request.getProblemId() : null);
            reply(response, result);
            log.info("internal grpc tool ok name={} duration_ms={}", request.getToolName(), elapsed(start));
        } catch (Exception ex) {
            fail(response, ex, "tool", request.getToolName(), start);
        }
    }

    @Override
    public void getUserLlm(UserRequest request, StreamObserver<JsonReply> response) {
        long start = System.nanoTime();
        try {
            reply(response, operations.userLlm(request.getUserPublicId()));
            log.info("internal grpc user llm ok duration_ms={}", elapsed(start));
        } catch (Exception ex) {
            fail(response, ex, "user_llm", "", start);
        }
    }

    @Override
    public void recordUsage(UsageRequest request, StreamObserver<JsonReply> response) {
        long start = System.nanoTime();
        try {
            reply(response, operations.recordUsage(request.getUserPublicId(), parseMap(request.getPayloadJson())));
            log.info("internal grpc usage ok duration_ms={}", elapsed(start));
        } catch (Exception ex) {
            fail(response, ex, "usage", "", start);
        }
    }

    private Map<String, Object> parseMap(String json) throws Exception {
        return json == null || json.isBlank() ? Map.of() : mapper.readValue(json, MAP_TYPE);
    }

    private void reply(StreamObserver<JsonReply> response, Map<String, Object> result) throws Exception {
        response.onNext(JsonReply.newBuilder().setJson(mapper.writeValueAsString(result)).build());
        response.onCompleted();
    }

    private void fail(StreamObserver<JsonReply> response, Exception ex, String method, String detail, long start) {
        Status status;
        if (ex instanceof ResponseStatusException web) {
            int code = web.getStatusCode().value();
            status = code == 400 ? Status.INVALID_ARGUMENT
                    : code == 403 ? Status.PERMISSION_DENIED
                    : code == 404 ? Status.NOT_FOUND : Status.INTERNAL;
        } else if (ex instanceof IllegalArgumentException) {
            status = Status.INVALID_ARGUMENT;
        } else {
            status = Status.INTERNAL;
        }
        log.warn("internal grpc {} failed detail={} duration_ms={}: {}", method, detail, elapsed(start), ex.toString());
        response.onError(status.withDescription("internal request failed").asRuntimeException());
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
