package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.FunctionTool;
import com.agentcore.domain.tool.Schemas;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolException;
import com.agentcore.domain.tool.ToolOutput;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** web_fetch：抓取网页并转成可读文本（纯客户端实现，任何模型都能用）。 */
public final class WebTools {

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

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
                .handler((a, ctx) -> {
                    String url = (String) a.get("url");
                    if (!url.matches("^https?://.+")) {
                        throw new ToolException("Only http(s) URLs are supported.");
                    }
                    int max = FileTools.num(a.get("max_chars"), 20_000);
                    HttpResponse<String> r;
                    try {
                        r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                                .header("User-Agent", "agentcore/0.1 (+web_fetch)").GET().build(), HttpResponse.BodyHandlers.ofString());
                    } catch (IOException e) {
                        throw new ToolException("Request failed: " + e.getMessage());
                    }
                    if (r.statusCode() >= 400) {
                        String b = r.body();
                        throw new ToolException("HTTP " + r.statusCode() + " for " + url + ": " + b.substring(0, Math.min(500, b.length())));
                    }
                    String ctype = r.headers().firstValue("content-type").orElse("");
                    String body = ctype.contains("html") ? htmlToText(r.body()) : r.body();
                    if (body.length() > max) {
                        body = body.substring(0, max) + "\n\n... [truncated, " + (body.length() - max) + " more chars]";
                    }
                    return ToolOutput.text("URL: " + r.uri() + "\n\n" + body);
                }).build();
    }

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    /** 简单的 HTML → 文本：去掉脚本样式，块级元素换行，链接保留地址。 */
    public static String htmlToText(String html) {
        String title = "";
        Matcher tm = TITLE.matcher(html);
        if (tm.find()) {
            title = decode(tm.group(1)).strip();
        }
        String s = html.replaceAll("(?is)<(script|style|noscript|svg|head|template)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?is)<h([1-3])[^>]*>", "\n\n#H$1 ");
        s = s.replaceAll("(?is)<li[^>]*>", "\n- ");
        s = s.replaceAll("(?is)<a\\s[^>]*href=[\"'](https?://[^\"']+)[\"'][^>]*>(.*?)</a>", "$2 ($1)");
        s = s.replaceAll("(?is)</?(p|div|br|tr|section|article|pre|table|header|footer|h[1-6])[^>]*>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = decode(s);
        s = s.replace("#H1 ", "# ").replace("#H2 ", "## ").replace("#H3 ", "### ");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").strip();
        return title.isEmpty() ? s : "# " + title + "\n\n" + s;
    }

    private static String decode(String s) {
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }
}
