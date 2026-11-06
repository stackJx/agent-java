package com.agentcore.interfaces.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 简单的 Bearer Token 鉴权：请求头必须是 {@code Authorization: Bearer <token>}，否则返回 401。
 * 用于没有接入统一认证的独立部署；已经有 Spring Security 的应用不需要配置 token。
 *
 * <p>两种 token 可以同时配置：
 * <ul>
 *   <li>共享 token（{@code agentcore.web.token}）：不对应具体用户，能访问所有会话（单用户或管理员）；</li>
 *   <li>每个用户一个 token（{@code agentcore.web.user-tokens}）：认出用户后放进请求属性 {@link WebCaller#USER_ATTRIBUTE}，
 *       这个用户只能看到、操作自己的会话。</li>
 * </ul>
 */
public final class BearerTokenInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 共享 token 的完整请求头；没有配置时为 null。 */
    private final byte[] sharedHeader;
    /** 用户名 → 这个用户的完整请求头。 */
    private final Map<String, byte[]> headersByUser = new LinkedHashMap<>();

    public BearerTokenInterceptor(String sharedToken) {
        this(sharedToken, Map.of());
    }

    /** @param tokensByUser 用户名 → token */
    public BearerTokenInterceptor(String sharedToken, Map<String, String> tokensByUser) {
        this.sharedHeader = sharedToken == null || sharedToken.isBlank() ? null : header(sharedToken);
        tokensByUser.forEach((user, token) -> {
            if (token != null && !token.isBlank()) {
                headersByUser.put(user, header(token));
            }
        });
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String given = request.getHeader("Authorization");
        if (given != null) {
            byte[] givenBytes = given.getBytes(StandardCharsets.UTF_8);
            if (sharedHeader != null && MessageDigest.isEqual(sharedHeader, givenBytes)) {
                return true;
            }
            for (Map.Entry<String, byte[]> user : headersByUser.entrySet()) {
                // 恒定时间比较，避免通过响应时间猜出 token
                if (MessageDigest.isEqual(user.getValue(), givenBytes)) {
                    request.setAttribute(WebCaller.USER_ATTRIBUTE, user.getKey());
                    return true;
                }
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getOutputStream().write("{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
        return false;
    }

    private static byte[] header(String token) {
        return (BEARER_PREFIX + token).getBytes(StandardCharsets.UTF_8);
    }
}
