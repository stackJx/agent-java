package com.agentcore.interfaces.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 简单的 Bearer Token 鉴权：请求头必须是 {@code Authorization: Bearer <token>}，否则返回 401。
 * 用于没有接入统一认证的独立部署；已经有 Spring Security 的应用不需要配置 token。
 */
public final class BearerTokenInterceptor implements HandlerInterceptor {

    private final byte[] expected;

    public BearerTokenInterceptor(String token) {
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String header = request.getHeader("Authorization");
        // 恒定时间比较，避免通过响应时间猜出 token
        if (header != null && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getOutputStream().write("{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
        return false;
    }
}
