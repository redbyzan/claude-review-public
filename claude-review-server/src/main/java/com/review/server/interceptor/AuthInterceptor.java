package com.review.server.interceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.review.server.service.MonitorService;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    private final String expectedSecret;
    private final MonitorService monitorService;

    public AuthInterceptor(@Value("${review.secret:}") String expectedSecret,
                           MonitorService monitorService) {
        this.expectedSecret = expectedSecret;
        this.monitorService = monitorService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // /review 또는 /review/conversation 경로가 아니면 인증 스킵
        String path = request.getServletPath();
        String normalizedPath = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1) : path;
        if (!"/review".equals(normalizedPath) && !"/review/conversation".equals(normalizedPath)) {
            return true;
        }

        String ip = getClientIp(request);
        List<String> errors = new ArrayList<>();

        // 1. 공유 시크릿 검증 (타이밍 공격 방어)
        String secret = request.getHeader("x-review-secret");
        if (expectedSecret != null && !expectedSecret.isBlank()) {
            if (secret == null || !MessageDigest.isEqual(
                    secret.getBytes(StandardCharsets.UTF_8),
                    expectedSecret.getBytes(StandardCharsets.UTF_8))) {
                errors.add("invalid_secret");
            }
        }

        // 2. GitHub repo 헤더 확인
        String ghRepo = request.getHeader("x-github-repo");
        if (ghRepo == null || !ghRepo.contains("/")) {
            errors.add("missing_github_repo");
        }

        // 3. Content-Type
        String contentType = request.getContentType();
        if (contentType == null || !contentType.contains("application/json")) {
            errors.add("invalid_content_type");
        }

        if (!errors.isEmpty()) {
            log.warn("[Auth] BLOCKED — ip:{} repo:{} reason:{}", ip, ghRepo, errors);
            monitorService.recordAuthBlocked(ghRepo, errors);
            response.sendError(404);
            return false;
        }

        log.info("[Auth] PASS — ip:{} repo:{} method:{} contentType:{} contentLength:{}",
                ip, ghRepo, request.getMethod(), contentType, request.getContentLength());
        return true;
    }

    static String getClientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
