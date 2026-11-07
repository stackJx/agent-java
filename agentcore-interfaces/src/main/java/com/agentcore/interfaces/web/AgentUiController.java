package com.agentcore.interfaces.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 自带 Web 页面的入口。页面公开，调用的 API 仍受 token / local-only 保护。
 *
 * <p>入口和直接访问 index.html 都经过此控制器，避免静态资源返回未替换的占位符。
 * API 前缀按请求注入，包含应用的 servlet context-path；不信任客户端自报的转发头。
 */
@Controller
@RequestMapping("${agentcore.web.ui.path:/agentcore}")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(name = "agentcore.web.enabled", matchIfMissing = true)
@ConditionalOnBooleanProperty(name = "agentcore.web.ui.enabled", matchIfMissing = true)
public class AgentUiController {

    private static final String API_BASE_PLACEHOLDER = "__AGENTCORE_API_BASE__";
    private static final String INDEX_RESOURCE = "/agentcore-ui/index.html";

    private final String apiBase;
    private final String indexHtml;

    /** @Value 也支持宿主应用直接扫描此 @Controller，而不仅是 starter 的 @Bean 注册。 */
    public AgentUiController(@Value("${agentcore.web.base-path:/api/agent}") String apiBase) {
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.indexHtml = loadIndex();
    }

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<String> index(HttpServletRequest request) {
        String requestApiBase = request.getContextPath() + apiBase;
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache())
                .body(indexHtml.replace(API_BASE_PLACEHOLDER, escapeHtmlAttribute(requestApiBase)));
    }

    private static String loadIndex() {
        try (InputStream in = AgentUiController.class.getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + INDEX_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escapeHtmlAttribute(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
