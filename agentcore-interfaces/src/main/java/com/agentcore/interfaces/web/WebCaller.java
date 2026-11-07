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

    /** 只由 BearerTokenInterceptor 在共享 token 校验通过时设置；绝不因为"没认出用户"而推断出来。 */
    public static final String ADMIN_ATTRIBUTE = WebCaller.class.getName() + ".admin";
    /** 只由 LocalOnlyInterceptor 在来源地址、Host、Origin 都确认是本机后设置。 */
    public static final String LOCAL_ATTRIBUTE = WebCaller.class.getName() + ".local";
    /** 应用自己的认证（Spring Security 等）里，拥有这个角色的用户可以管理模型配置。 */
    public static final String ADMIN_ROLE = "AGENTCORE_ADMIN";

    /**
     * 能不能管理模型配置（含 API key）。三种情况之一：
     * <ol>
     *   <li>用共享 token（agentcore.web.token）访问；</li>
     *   <li>应用自己的认证登录的用户，并且有 {@value #ADMIN_ROLE} 角色；</li>
     *   <li>没有配置任何 token 时的本机访问（单用户本机部署，用的人就是机器的主人）。</li>
     * </ol>
     * 用用户 token（user-tokens）访问的普通用户不是管理员。
     */
    public static boolean isAdmin(HttpServletRequest request) {
        boolean sharedToken = Boolean.TRUE.equals(request.getAttribute(ADMIN_ATTRIBUTE));
        boolean adminRole = request.getUserPrincipal() != null && request.isUserInRole(ADMIN_ROLE);
        boolean anonymous = request.getUserPrincipal() == null && request.getAttribute(USER_ATTRIBUTE) == null;
        boolean localOwner = anonymous && Boolean.TRUE.equals(request.getAttribute(LOCAL_ATTRIBUTE));
        return sharedToken || adminRole || localOwner;
    }

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
