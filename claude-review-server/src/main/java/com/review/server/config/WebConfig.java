package com.review.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.review.server.interceptor.AuthInterceptor;
import com.review.server.interceptor.RateLimitInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final AuthInterceptor authInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor, AuthInterceptor authInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns("/", "/css/**", "/js/**", "/images/**", "/favicon.ico", "*.yml", "/actuator/**")
            .order(1);
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/review", "/review/conversation", "/review/rebuttal")
            .order(2);
    }
}
