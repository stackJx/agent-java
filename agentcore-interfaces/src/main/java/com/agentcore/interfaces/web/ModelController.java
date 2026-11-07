package com.agentcore.interfaces.web;

import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型列表和模型配置接口。
 *
 * <pre>
 * GET    {base}/models                          可选的模型（所有能访问接口的人）
 * GET    {base}/model-settings                  完整配置（管理员）
 * PUT    {base}/model-settings/default          {"model_id": "..."} 设为默认（管理员）
 * PUT    {base}/model-settings/{id}             新建或修改一个配置（管理员）
 * DELETE {base}/model-settings/{id}             删除一个配置（管理员）
 * </pre>
 * 谁是管理员见 {@link WebCaller#isAdmin}。返回值都不缓存，也都不包含 API key。
 *
 * <p>类上的条件注解是给"应用自己扫描了 com.agentcore 包"的情况用的：关掉 Web 或模型配置时，扫描到也不注册。
 */
@RestController
@RequestMapping("${agentcore.web.base-path:/api/agent}")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(name = "agentcore.web.enabled", matchIfMissing = true)
@ConditionalOnBooleanProperty(name = "agentcore.web.models.enabled", matchIfMissing = true)
public final class ModelController {

    /** 请求体上限：一个配置远用不了这么大，超过的直接拒绝。 */
    private static final int MAX_BODY_CHARS = 24_000;

    private final ModelCatalog catalog;

    public ModelController(ModelCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/models")
    public ResponseEntity<String> models(HttpServletRequest request) {
        return json(200, catalog.models(WebCaller.isAdmin(request)));
    }

    @GetMapping("/model-settings")
    public ResponseEntity<String> settings(HttpServletRequest request) {
        requireAdmin(request);
        return json(200, catalog.settings());
    }

    @PutMapping("/model-settings/default")
    public ResponseEntity<String> setDefault(@RequestBody(required = false) String body, HttpServletRequest request) {
        requireAdmin(request);
        JsonNode modelId = parseObject(body).get("model_id");
        if (modelId == null || !modelId.isTextual()) {
            throw new ModelCatalog.Failure(400, "model_id is required");
        }
        catalog.setDefault(modelId.textValue());
        return json(200, catalog.settings());
    }

    @PutMapping("/model-settings/{id}")
    public ResponseEntity<String> put(@PathVariable String id, @RequestBody(required = false) String body,
                                      HttpServletRequest request) {
        requireAdmin(request);
        catalog.put(id, parseObject(body));
        return json(200, catalog.settings());
    }

    @DeleteMapping("/model-settings/{id}")
    public ResponseEntity<String> delete(@PathVariable String id, HttpServletRequest request) {
        requireAdmin(request);
        catalog.delete(id);
        return json(200, catalog.settings());
    }

    // ------------------------------------------------------------------ 错误处理

    @ExceptionHandler(ModelCatalog.Failure.class)
    public ResponseEntity<String> failure(ModelCatalog.Failure failure) {
        return json(failure.status(), Json.object().put("error", failure.getMessage()));
    }

    /** 其他异常只返回固定的文字：异常信息里可能有请求体（含 key）、厂商错误或服务端路径。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unexpected(Exception ignored) {
        return json(500, Json.object().put("error", "model settings operation failed"));
    }

    // ------------------------------------------------------------------ 工具方法

    private static void requireAdmin(HttpServletRequest request) {
        if (!WebCaller.isAdmin(request)) {
            throw new ModelCatalog.Failure(403, "model administration required");
        }
    }

    private static JsonNode parseObject(String body) {
        if (body == null || body.length() > MAX_BODY_CHARS) {
            throw new ModelCatalog.Failure(400, "invalid JSON body");
        }
        JsonNode value;
        try {
            value = Json.read(body);
        } catch (RuntimeException notJson) {
            throw new ModelCatalog.Failure(400, "invalid JSON body");
        }
        if (value == null || !value.isObject()) {
            throw new ModelCatalog.Failure(400, "invalid JSON body");
        }
        return value;
    }

    private static ResponseEntity<String> json(int status, JsonNode body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(Json.write(body));
    }
}
