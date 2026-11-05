package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** web_fetch：抓取网页并转成可读文本（纯客户端实现，任何模型都能用）。 */
public final class WebTools {

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    private static final int DEFAULT_MAX_CHARS = 20_000;
    private static final int MAX_ERROR_CHARS = 500;

    private WebTools() {
    }

    public static Tool webFetch() {
        return FunctionTool.builder("web_fetch")
                .description("Fetch a URL over HTTP(S) and return its content as readable text (HTML is converted to text).")
                .schema(Schemas.object()
                        .string("url", "The http(s) URL to fetch.", true)
                        .integer("max_chars", "Maximum characters of content to return (default 20000).", false)
                        .build())
                .readOnly(true)
                .timeout(Duration.ofSeconds(60))
                .handler(WebTools::fetch)
                .build();
    }

    private static ToolOutput fetch(Map<String, Object> args, ToolContext ctx) throws InterruptedException {
        String url = (String) args.get("url");
        if (!url.matches("^https?://.+")) {
            throw new ToolException("Only http(s) URLs are supported.");
        }
        int maxChars = ToolArgs.integer(args, "max_chars", DEFAULT_MAX_CHARS);
        HttpResponse<String> response;
        try {
            response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "agentcore/0.1 (+web_fetch)").GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ToolException("Request failed: " + e.getMessage());
        }
        if (response.statusCode() >= 400) {
            String error = response.body();
            throw new ToolException("HTTP " + response.statusCode() + " for " + url + ": "
                    + error.substring(0, Math.min(MAX_ERROR_CHARS, error.length())));
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        String body = contentType.contains("html") ? htmlToText(response.body()) : response.body();
        if (body.length() > maxChars) {
            body = body.substring(0, maxChars) + "\n\n... [truncated, " + (body.length() - maxChars) + " more chars]";
        }
        return ToolOutput.text("URL: " + response.uri() + "\n\n" + body);
    }

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    /** 简单的 HTML → 文本：去掉脚本样式，块级元素换行，链接保留地址。 */
    public static String htmlToText(String html) {
        String title = "";
        Matcher titleMatch = TITLE.matcher(html);
        if (titleMatch.find()) {
            title = decode(titleMatch.group(1)).strip();
        }
        String text = html.replaceAll("(?is)<(script|style|noscript|svg|head|template)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?is)<h([1-3])[^>]*>", "\n\n#H$1 ");                // 标题先打个标记，最后换成 Markdown
        text = text.replaceAll("(?is)<li[^>]*>", "\n- ");
        text = text.replaceAll("(?is)<a\\s[^>]*href=[\"'](https?://[^\"']+)[\"'][^>]*>(.*?)</a>", "$2 ($1)");
        text = text.replaceAll("(?is)</?(p|div|br|tr|section|article|pre|table|header|footer|h[1-6])[^>]*>", "\n");
        text = text.replaceAll("(?s)<[^>]+>", "");
        text = decode(text);
        text = text.replace("#H1 ", "# ").replace("#H2 ", "## ").replace("#H3 ", "### ");
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").strip();
        return title.isEmpty() ? text : "# " + title + "\n\n" + text;
    }

    private static String decode(String text) {
        return text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }
}
