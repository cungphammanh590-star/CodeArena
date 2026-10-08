package com.codearena.business.internal.grpc;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InternalGrpcServer implements SmartLifecycle {
    private final InternalGrpcService service;
    private volatile boolean running;
    private Server server;

    @Value("${codearena.internal.grpc-port:9092}")
    private int port;

    @Value("${codearena.internal.grpc-host:127.0.0.1}")
    private String host;

    @Override
    public synchronized void start() {
        if (running) return;
        try {
            server = NettyServerBuilder.forAddress(new InetSocketAddress(host, port))
                    .addService(ServerInterceptors.intercept(service, service.authInterceptor()))
                    .build()
                    .start();
            running = true;
            log.info("internal grpc listening host={} port={}", host, port);
        } catch (IOException ex) {
            throw new IllegalStateException("internal grpc failed to start", ex);
        }
    }

    @Override
    public synchronized void stop() {
        if (server != null) server.shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
