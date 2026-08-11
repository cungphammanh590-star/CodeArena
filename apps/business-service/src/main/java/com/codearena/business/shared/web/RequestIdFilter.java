package com.codearena.business.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 透传/生成 X-Request-Id，写入 MDC 供 JSON 日志检索。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HDR_REQUEST_ID = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "request_id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HDR_REQUEST_ID);
        String requestId =
                (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming.trim();
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(HDR_REQUEST_ID, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}
