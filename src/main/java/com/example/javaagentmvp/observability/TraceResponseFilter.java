package com.example.javaagentmvp.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Supplier;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TraceResponseFilter extends OncePerRequestFilter {

    private final ObjectProvider<Tracer> tracerProvider;

    public TraceResponseFilter(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        filterChain.doFilter(request, response);
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer != null && tracer.currentSpan() != null && tracer.currentSpan().context() != null) {
            String traceId = tracer.currentSpan().context().traceId();
            if (traceId != null && !traceId.isBlank()) {
                response.setHeader("X-Trace-Id", traceId);
            }
        }
    }

    public static <T> T observe(
            ObservationRegistry registry, String name, String lowCardinalityKey, Supplier<T> supplier) {
        return Observation.createNotStarted(name, registry)
                .lowCardinalityKeyValue("scope", lowCardinalityKey)
                .observe(supplier);
    }
}
