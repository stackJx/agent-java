package com.agentcore.interfaces.web;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;

/**
 * 这次请求是哪个用户发的，按顺序看：
 * <ol>
 *   <li>{@link BearerTokenInterceptor} 按 {@code agentcore.web.user-tokens} 认出的用户；</li>
 *   <li>应用自己的认证（Spring Security 等）登录的用户：{@link HttpServletRequest#getUserPrincipal()}；</li>
 *   <li>都没有：返回 null，表示单用户部署（或用共享 token 访问的管理员），可以访问所有会话。</li>
 * </ol>
 */
public final class WebCaller {

    /** BearerTokenInterceptor 把认出的用户名放在这个请求属性里。 */
    public static final String USER_ATTRIBUTE = WebCaller.class.getName() + ".user";

    private WebCaller() {
    }

    public static String userOf(HttpServletRequest request) {
        if (request.getAttribute(USER_ATTRIBUTE) instanceof String user) {
            return user;
        }
        Principal principal = request.getUserPrincipal();
        return principal == null ? null : principal.getName();
    }
}
