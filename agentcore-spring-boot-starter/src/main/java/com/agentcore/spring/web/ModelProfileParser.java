package com.agentcore.spring.web;

import com.agentcore.interfaces.web.ModelCatalog.Failure;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 把页面提交（或配置文件里保存）的 JSON 校验并转换成 {@link ModelProfile}。
 *
 * <p>所有错误信息都是固定的文字，不会带出提交的内容 —— 提交里可能有 API key。
 *
 * <p>API key 的规则：
 * <ul>
 *   <li>不传或传空字符串：保留原来的 key；</li>
 *   <li>clear_api_key = true：删除已保存的 key（不能同时传新 key）；</li>
 *   <li>改了接口地址的来源（协议 + 主机 + 端口）或协议类型时，必须重新填 key 或明确删除 ——
 *       否则旧 key 会被发给一个新的地址。</li>
 * </ul>
 */
final class ModelProfileParser {

    static final String MOCK = "mock";
    private static final Set<String> TYPES = Set.of("anthropic", "openai", "openai-responses", MOCK);
    /** 允许出现的字段（has_api_key 是页面拿到的字段，原样提交回来时忽略）。 */
    private static final Set<String> FIELDS = Set.of("id", "label", "type", "model", "base_url", "has_api_key",
            "api_key", "clear_api_key", "max_tokens", "context_window");
    private static final String ANTHROPIC_DEFAULT_URL = "https://api.anthropic.com";
    private static final String OPENAI_DEFAULT_URL = "https://api.openai.com/v1";

    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_LABEL_LENGTH = 120;
    private static final int MAX_TYPE_LENGTH = 32;
    private static final int MAX_MODEL_LENGTH = 256;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_KEY_LENGTH = 8192;
    private static final int DEFAULT_MAX_TOKENS = 16_000;
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int MAX_MAX_TOKENS = 1_000_000;
    private static final int MAX_CONTEXT_WINDOW = 10_000_000;
    /** API key 只能是可见的 ASCII 字符（不含空格），防止换行等字符混进 HTTP 头。 */
    private static final String PRINTABLE_ASCII = "[\\x21-\\x7e]+";

    private ModelProfileParser() {
    }

    /**
     * @param previous 同一 ID 原来的配置（新建时为 null），用来保留 API key
     */
    static ModelProfile parse(String id, JsonNode body, ModelProfile previous) {
        requireKnownFields(body);
        if (body.has("id") && !id.equals(text(body, "id", MAX_ID_LENGTH, null))) {
            throw new Failure(400, "profile id mismatch");
        }
        String type = text(body, "type", MAX_TYPE_LENGTH, null);
        if (!TYPES.contains(type)) {
            throw new Failure(400, "invalid model type");
        }
        boolean mock = MOCK.equals(type);
        String label = text(body, "label", MAX_LABEL_LENGTH, id);
        String model = text(body, "model", MAX_MODEL_LENGTH, mock ? MOCK : null);
        String baseUrl = baseUrl(body, type);
        int maxTokens = integer(body, "max_tokens", DEFAULT_MAX_TOKENS, MAX_MAX_TOKENS);
        int contextWindow = integer(body, "context_window", DEFAULT_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW);
        if (maxTokens > contextWindow) {
            throw new Failure(400, "max_tokens must not exceed context_window");
        }
        String apiKey = mock ? "" : apiKey(body, type, baseUrl, previous);
        return new ModelProfile(id, label, type, model, baseUrl, apiKey, maxTokens, contextWindow);
    }

    private static void requireKnownFields(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new Failure(400, "profile must be an object");
        }
        body.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new Failure(400, "unsupported profile field");
            }
        });
    }

    /** 没填时用协议的官方地址；mock 不需要地址。 */
    private static String baseUrl(JsonNode body, String type) {
        String baseUrl = text(body, "base_url", MAX_URL_LENGTH, "");
        if (baseUrl.isEmpty() && !MOCK.equals(type)) {
            baseUrl = "anthropic".equals(type) ? ANTHROPIC_DEFAULT_URL : OPENAI_DEFAULT_URL;
        }
        if (!baseUrl.isEmpty()) {
            requireHttpEndpoint(baseUrl);
        }
        return baseUrl;
    }

    /** 按类说明里的规则决定最终保存的 API key。 */
    private static String apiKey(JsonNode body, String type, String baseUrl, ModelProfile previous) {
        boolean clear = clearFlag(body);
        String supplied = text(body, "api_key", MAX_KEY_LENGTH, "");
        if (!supplied.isEmpty() && !supplied.matches(PRINTABLE_ASCII)) {
            throw new Failure(400, "invalid api_key");
        }
        if (clear && !supplied.isEmpty()) {
            throw new Failure(400, "api_key and clear_api_key conflict");
        }
        if (clear) {
            return "";
        }
        if (!supplied.isEmpty()) {
            return supplied;
        }
        if (previous == null || !previous.hasApiKey()) {
            return "";
        }
        boolean endpointChanged = !origin(previous.baseUrl()).equals(origin(baseUrl)) || !previous.type().equals(type);
        if (endpointChanged) {
            throw new Failure(400, "endpoint or type changed; provide api_key or clear_api_key");
        }
        return previous.apiKey();
    }

    private static boolean clearFlag(JsonNode body) {
        if (!body.has("clear_api_key")) {
            return false;
        }
        if (!body.get("clear_api_key").isBoolean()) {
            throw new Failure(400, "clear_api_key must be boolean");
        }
        return body.get("clear_api_key").booleanValue();
    }

    // ------------------------------------------------------------------ 字段读取

    /**
     * 读一个文字字段。fallback 为 null 表示必填；只有 fallback 是空字符串的字段（地址、key）才允许填空白。
     * 不允许控制字符，防止换行等字符混进请求头或日志。
     */
    static String text(JsonNode body, String field, int maxLength, String fallback) {
        JsonNode value = body.get(field);
        if (value == null) {
            if (fallback == null) {
                throw new Failure(400, field + " is required");
            }
            return fallback;
        }
        boolean valid = value.isTextual()
                && value.textValue().length() <= maxLength
                && value.textValue().chars().noneMatch(Character::isISOControl);
        if (!valid) {
            throw new Failure(400, "invalid " + field);
        }
        String text = value.textValue();
        boolean blankAllowed = fallback != null && fallback.isEmpty();
        if (text.isBlank() && !blankAllowed) {
            throw new Failure(400, "invalid " + field);
        }
        return text;
    }

    private static int integer(JsonNode body, String field, int fallback, int max) {
        JsonNode value = body.get(field);
        if (value == null) {
            return fallback;
        }
        boolean valid = value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 1 && value.intValue() <= max;
        if (!valid) {
            throw new Failure(400, "invalid " + field);
        }
        return value.intValue();
    }

    // ------------------------------------------------------------------ 接口地址

    /** 只接受 http(s) 地址，不能带用户名密码、查询参数或片段（这些地方常被用来夹带凭据）。 */
    private static URI requireHttpEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean valid = (scheme.equals("http") || scheme.equals("https"))
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getPort() != 0 && uri.getPort() <= 65535
                    && !value.contains("\\");
            if (!valid) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new Failure(400, "invalid base_url; use an http(s) URL without userinfo, query or fragment");
        }
    }

    /** 地址的来源：协议 + 主机（不分大小写）+ 端口（没写时按协议的默认端口）。 */
    private static String origin(String url) {
        if (url.isEmpty()) {
            return "";
        }
        URI uri = requireHttpEndpoint(url);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int defaultPort = scheme.equals("https") ? 443 : 80;
        int port = uri.getPort() < 0 ? defaultPort : uri.getPort();
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
