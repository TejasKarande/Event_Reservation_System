package com.ticketbooking.system.logging;

import com.ticketbooking.system.observability.ObservabilityMetrics;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);
    private final ObservabilityMetrics metrics;

    public RequestIdFilter(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    protected void doFilterInternal(HttpServletRequest q, HttpServletResponse p, FilterChain c)
            throws ServletException, IOException {
        String id = q.getHeader("X-Request-Id");
        if (id == null || id.isBlank())
            id = UUID.randomUUID().toString();
        RequestIds.set(id);
        MDC.put("request_id", id);
        p.setHeader("X-Request-Id", id);
        long start = System.nanoTime();
        try {
            c.doFilter(q, p);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            metrics.recordHttp(q.getMethod(), route(q), p.getStatus());
            LOG.info("{}", event(Instant.now().toString(), id, q, p.getStatus(), durationMs));
            MDC.remove("request_id");
            RequestIds.clear();
        }
    }

    private static String event(String timestamp, String requestId, HttpServletRequest request, int status, long durationMs) {
        String path = request.getRequestURI();
        String[] parts = path.split("/");
        String showId = parts.length >= 3 && "shows".equals(parts[1]) ? parts[2] : null;
        String reservationId = parts.length >= 3 && "reservations".equals(parts[1]) ? parts[2] : null;
        Object user = request.getAttribute("authenticated_user_id");
        return "{\"timestamp\":\"" + timestamp + "\",\"request_id\":" + string(requestId)
                + ",\"method\":" + string(request.getMethod()) + ",\"path\":" + string(path)
                + ",\"user_id\":" + string(user == null ? null : user.toString()) + ",\"show_id\":" + string(showId)
                + ",\"reservation_id\":" + string(reservationId) + ",\"result\":\"HTTP_" + status
                + "\",\"duration_ms\":" + durationMs + "}";
    }

    private static String string(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String route(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.matches("/shows/[^/]+/reserve")) {
            return "/shows/{id}/reserve";
        }
        if (path.matches("/shows/[^/]+/reconciliation")) {
            return "/shows/{id}/reconciliation";
        }
        if (path.matches("/shows/[^/]+")) {
            return "/shows/{id}";
        }
        if (path.matches("/reservations/[^/]+/cancel")) {
            return "/reservations/{id}/cancel";
        }
        if (path.startsWith("/actuator/health")) {
            return "/actuator/health";
        }
        return path;
    }
}
