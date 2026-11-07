package com.agentcore.interfaces.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 没有配置 token 时的保护：只接受本机发来的请求，否则返回 403。三项都要满足：
 * <ol>
 *   <li>来源地址是本机（挡住别的机器）；</li>
 *   <li>Host 头是 localhost / 127.0.0.1 / [::1]（挡住 DNS rebinding：恶意网页把自己的域名解析到 127.0.0.1，
 *       浏览器会把它当成"同源"放行，但 Host 头仍是恶意域名）；</li>
 *   <li>有 Origin 头时，它也必须是本机地址（挡住普通网页发来的跨站请求）。</li>
 * </ol>
 * 命令行工具（curl 等）不带 Origin 头，不受影响。需要远程访问时请配置 {@code agentcore.web.token}。
 */
public final class LocalOnlyInterceptor implements HandlerInterceptor {

    private static final Set<String> LOCAL_HOST_NAMES = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String problem = rejectionReason(request);
        if (problem == null) {
            request.setAttribute(WebCaller.LOCAL_ATTRIBUTE, Boolean.TRUE);
            return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        String body = "{\"error\":\"" + problem + "; configure agentcore.web.token to allow non-local clients\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return false;
    }

    /** 不满足的那一项；都满足时返回 null。自己写入口（例如 WebSocket）时也可以用它做同样的检查。 */
    public static String rejectionReason(HttpServletRequest request) {
        if (!isLoopbackAddress(request.getRemoteAddr())) {
            return "only local clients are accepted";
        }
        if (!isLocalHostName(hostWithoutPort(request.getHeader("Host")))) {
            return "the Host header must be localhost";
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !isLocalHostName(originHost(origin))) {
            return "cross-site requests are not accepted";
        }
        return null;
    }

    private static boolean isLoopbackAddress(String address) {
        if (address == null) {
            return false;
        }
        try {
            // 远程地址是 IP 字面量，不会触发 DNS 查询
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isLocalHostName(String host) {
        return host != null && LOCAL_HOST_NAMES.contains(host.toLowerCase(Locale.ROOT));
    }

    /** "localhost:8080" → "localhost"；"[::1]:8080" → "[::1]"。 */
    static String hostWithoutPort(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String host = hostHeader.strip();
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    /** Origin 头里的主机名；"null"（沙箱页面、本地文件）或无法解析时返回 null，按跨站处理。 */
    private static String originHost(String origin) {
        try {
            return URI.create(origin.strip()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
