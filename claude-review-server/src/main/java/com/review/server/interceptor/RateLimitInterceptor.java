package com.review.server.interceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.review.server.service.MonitorService;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final int WINDOW_MS = 60_000;
    private static final int MAX_REQUESTS = 10;

    private final ConcurrentHashMap<String, Window> store = new ConcurrentHashMap<>();
    private final MonitorService monitorService;

    public RateLimitInterceptor(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = getClientIp(request);
        long now = System.currentTimeMillis();

        Window window = store.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.start > WINDOW_MS) {
                return new Window(1, now);
            }
            existing.count.incrementAndGet();
            return existing;
        });

        int count = window.count.get();
        if (count > MAX_REQUESTS) {
            int resetIn = (int) Math.ceil((window.start + WINDOW_MS - now) / 1000.0);
            log.warn("[RateLimit] BLOCKED — ip:{} count:{} resetIn:{}s", ip, count, resetIn);
            monitorService.recordRateLimitHit(request.getServletPath());
            response.setHeader("Retry-After", String.valueOf(resetIn));
            response.sendError(429, "Too many requests");
            return false;
        }

        return true;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> now - entry.getValue().start > WINDOW_MS * 2);
    }

    private String getClientIp(HttpServletRequest request) {
        return AuthInterceptor.getClientIp(request);
    }

    private static class Window {
        final AtomicInteger count;
        final long start;

        Window(int count, long start) {
            this.count = new AtomicInteger(count);
            this.start = start;
        }
    }
}
