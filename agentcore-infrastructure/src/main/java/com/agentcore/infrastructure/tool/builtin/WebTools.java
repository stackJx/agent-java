package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.PermissionLevel;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * web_fetch：抓取网页并转成可读文本（纯客户端实现，任何模型都能用）。
 *
 * <p>安全约束：
 * <ul>
 *   <li>默认每次都要询问（可以用规则按网址放行，例如 {@code web_fetch(https://docs.example.com/*)}）——
 *       否则网页里的提示注入就能让模型把读到的密钥拼进网址发出去；</li>
 *   <li>默认拒绝访问本机、内网、链路本地地址（例如云主机元数据 169.254.169.254），每一跳重定向都重新检查；</li>
 *   <li>响应体最多读 {@value #MAX_BODY_BYTES} 字节。</li>
 * </ul>
 */
public final class WebTools {

    /** 自己处理重定向，才能在每一跳之前检查目标地址。 */
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(20)).build();

    private static final int DEFAULT_MAX_CHARS = 20_000;
    private static final int MAX_ERROR_CHARS = 500;
    /** 响应体最多读多少字节，再多就截断（防止一个超大文件撑爆内存）。 */
    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([\\w.:-]+)", Pattern.CASE_INSENSITIVE);

    private WebTools() {
    }

    /** 默认配置：不允许访问本机和内网地址。 */
    public static Tool webFetch() {
        return webFetch(false);
    }

    /** @param allowPrivateNetwork true 时允许访问本机、内网地址（例如抓本地开发服务器的页面） */
    public static Tool webFetch(boolean allowPrivateNetwork) {
        return FunctionTool.builder("web_fetch")
                .description("Fetch a URL over HTTP(S) and return its content as readable text (HTML is converted to text)."
                        + (allowPrivateNetwork ? "" : " Local and private network addresses are not allowed."))
                .schema(Schemas.object()
                        .string("url", "The http(s) URL to fetch.", true)
                        .integer("max_chars", "Maximum characters of content to return (default 20000).", false)
                        .build())
                .readOnly(true)                    // 不修改状态：可以和其他只读工具并行
                .permission(PermissionLevel.ASK)   // 但会把数据发到外面：默认要询问
                .timeout(Duration.ofSeconds(60))
                .handler((args, ctx) -> fetch(args, ctx, allowPrivateNetwork))
                .build();
    }

    private static ToolOutput fetch(Map<String, Object> args, ToolContext ctx, boolean allowPrivateNetwork) throws InterruptedException {
        String url = (String) args.get("url");
        if (!url.matches("^https?://.+")) {
            throw new ToolException("Only http(s) URLs are supported.");
        }
        int maxChars = ToolArgs.integer(args, "max_chars", DEFAULT_MAX_CHARS);
        HttpResponse<InputStream> response = sendFollowingRedirects(URI.create(url), allowPrivateNetwork);
        String contentType = response.headers().firstValue("content-type").orElse("");
        byte[] body = readBounded(response.body());
        if (response.statusCode() >= 400) {
            String error = new String(body, charsetOf(contentType));
            throw new ToolException("HTTP " + response.statusCode() + " for " + response.uri() + ": "
                    + error.substring(0, Math.min(MAX_ERROR_CHARS, error.length())));
        }
        String text = contentType.contains("html") ? htmlToText(parseHtml(body, contentType, response.uri()))
                : new String(body, charsetOf(contentType));
        if (body.length >= MAX_BODY_BYTES) {
            text += "\n\n... [response truncated at " + MAX_BODY_BYTES + " bytes]";
        }
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + "\n\n... [truncated, " + (text.length() - maxChars) + " more chars]";
        }
        return ToolOutput.text("URL: " + response.uri() + "\n\n" + text);
    }

    /** 发 GET 请求，最多跟随 {@value #MAX_REDIRECTS} 次重定向；每一跳之前都检查目标地址。 */
    private static HttpResponse<InputStream> sendFollowingRedirects(URI start, boolean allowPrivateNetwork) throws InterruptedException {
        URI target = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!allowPrivateNetwork) {
                requirePublicHost(target);
            }
            HttpResponse<InputStream> response = send(target);
            String location = response.headers().firstValue("location").orElse(null);
            if (!isRedirect(response.statusCode()) || location == null) {
                return response;
            }
            closeQuietly(response.body());
            target = target.resolve(location);
            if (!"http".equals(target.getScheme()) && !"https".equals(target.getScheme())) {
                throw new ToolException("Refusing to follow a redirect to " + target);
            }
        }
        throw new ToolException("Too many redirects (more than " + MAX_REDIRECTS + ") starting from " + start);
    }

    private static HttpResponse<InputStream> send(URI target) throws InterruptedException {
        try {
            return HTTP.send(HttpRequest.newBuilder(target).timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "agentcore/0.1 (+web_fetch)").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ToolException("Request failed: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ToolException("Invalid URL: " + target);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 目标主机解析出的地址里，只要有一个是本机、内网、链路本地或组播地址就拒绝。
     * （检查和连接之间仍有 DNS 被换掉的可能；需要更强的隔离时请在网络层限制出口。）
     */
    static void requirePublicHost(URI target) {
        String host = target.getHost();
        if (host == null) {
            throw new ToolException("Invalid URL (no host): " + target);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ToolException("Unknown host: " + host);
        }
        for (InetAddress address : addresses) {
            if (isPrivateAddress(address)) {
                throw new ToolException("Refusing to fetch " + target + ": " + host + " resolves to a local or private network address ("
                        + address.getHostAddress() + ")");
            }
        }
    }

    static boolean isPrivateAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            boolean thisNetwork = first == 0;                                   // 0.0.0.0/8
            boolean carrierGradeNat = first == 100 && second >= 64 && second <= 127; // 100.64.0.0/10
            return thisNetwork || carrierGradeNat;
        }
        if (address instanceof Inet6Address) {
            boolean uniqueLocal = (raw[0] & 0xfe) == 0xfc;                      // fc00::/7
            return uniqueLocal;
        }
        return false;
    }

    /** 最多读 {@value #MAX_BODY_BYTES} 字节，然后关掉连接（剩下的不再下载）。 */
    private static byte[] readBounded(InputStream body) {
        try (InputStream in = body) {
            return in.readNBytes(MAX_BODY_BYTES);
        } catch (IOException e) {
            throw new ToolException("Failed to read the response: " + e.getMessage());
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 重定向响应的正文不需要
        }
    }

    /** Content-Type 里的 charset；没写或不认识时用 UTF-8。 */
    private static Charset charsetOf(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1).toUpperCase(Locale.ROOT));
            } catch (RuntimeException unknown) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** HTML 交给 jsoup 解码：头部没写 charset 时，jsoup 会看页面里的 meta 标签（GBK 页面也能正确显示）。 */
    private static Document parseHtml(byte[] body, String contentType, URI uri) {
        String declared = CHARSET.matcher(contentType).find() ? charsetOf(contentType).name() : null;
        try {
            return Jsoup.parse(new ByteArrayInputStream(body), declared, uri.toString());
        } catch (IOException e) {
            throw new ToolException("Failed to parse the page: " + e.getMessage());
        }
    }

    /**
     * HTML → 便于模型阅读的文字（jsoup 解析）：去掉脚本、样式等不可见内容，正文交给 {@link TextExtractor} 逐个节点转换，
     * 最后整理空白，网页标题放在最前面。
     */
    public static String htmlToText(String html) {
        return htmlToText(Jsoup.parse(html));
    }

    private static String htmlToText(Document doc) {
        String title = doc.title().strip();
        doc.select("script, style, noscript, svg, template, head").remove();
        TextExtractor extractor = new TextExtractor();
        NodeTraversor.traverse(extractor, doc.body());
        String body = tidyWhitespace(extractor.text());
        return title.isEmpty() ? body : "# " + title + "\n\n" + body;
    }

    /** 连续空格合并成一个，行首行尾的空格去掉，最多保留一个空行。 */
    private static String tidyWhitespace(String text) {
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /**
     * 深度优先遍历 DOM，边走边拼文字：h1～h3 变成 Markdown 标题，列表项前加 "- "，表格一行的单元格放在同一行，
     * 块级元素前后换行，绝对地址的链接写成 "文字 (地址)"。
     */
    private static final class TextExtractor implements NodeVisitor {
        private final StringBuilder text = new StringBuilder();

        String text() {
            return text.toString();
        }

        /** 进入一个节点：文字直接追加；元素按类型加标题符号、列表符号或换行。 */
        @Override
        public void head(Node node, int depth) {
            if (node instanceof TextNode textNode) {
                boolean preformatted = textNode.parent() instanceof Element parent && parent.normalName().equals("pre");
                text.append(preformatted ? textNode.getWholeText() : textNode.text());
                return;
            }
            if (!(node instanceof Element element)) {
                return;
            }
            switch (element.normalName()) {
                case "h1", "h2", "h3" -> text.append("\n\n").append("#".repeat(element.normalName().charAt(1) - '0')).append(' ');
                case "li" -> text.append("\n- ");
                case "br" -> text.append('\n');
                case "td", "th" -> text.append(' ');
                default -> {
                    if (element.isBlock()) {
                        text.append('\n');
                    }
                }
            }
        }

        /** 离开一个元素：链接补上地址，块级元素结束时换行（列表项和单元格在进入时已经处理过）。 */
        @Override
        public void tail(Node node, int depth) {
            if (!(node instanceof Element element)) {
                return;
            }
            switch (element.normalName()) {
                case "a" -> {
                    String href = element.attr("href");
                    if (href.startsWith("http://") || href.startsWith("https://")) {
                        text.append(" (").append(href).append(')');
                    }
                }
                case "li", "td", "th" -> { }
                default -> {
                    if (element.isBlock()) {
                        text.append('\n');
                    }
                }
            }
        }
    }
}
