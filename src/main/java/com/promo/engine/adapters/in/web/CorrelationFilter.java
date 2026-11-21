package com.promo.engine.adapters.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_CHANNEL = "X-Channel";

    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_CHANNEL = "channel";

    private final String defaultChannel;

    public CorrelationFilter(@Value("${promotions.engine.default-channel:APP_CHECKOUT}") String defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = nonBlankOrDefault(request.getHeader(HEADER_CORRELATION_ID),
                "corr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        String traceId = nonBlankOrDefault(request.getHeader(HEADER_TRACE_ID),
                UUID.randomUUID().toString().replace("-", ""));
        String channel = nonBlankOrDefault(request.getHeader(HEADER_CHANNEL), defaultChannel);

        MDC.put(MDC_CORRELATION_ID, correlationId);
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_CHANNEL, channel);

        response.setHeader(HEADER_CORRELATION_ID, correlationId);
        response.setHeader(HEADER_TRACE_ID, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_CHANNEL);
        }
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
