package com.agentcore.infrastructure.tool.builtin;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.permission.service.PermissionPolicy;
import com.agentcore.domain.permission.service.RulePermissionPolicy;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.PermissionLevel;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 内置工具的安全约束和资源上限：空参数、编码、超大输出、工作目录之外、私有网络。 */
class BuiltinToolLimitsTest {

    @TempDir
    Path dir;

    ToolContext context() {
        return new ToolContext(dir, "c1", "call", new ConcurrentHashMap<>(), message -> { }, new CancellationToken(), Map.of(), 0);
    }

    Tool tool(String name) {
        return BuiltinTools.all().get(name);
    }

    @Test
    void editWithEmptyOldStringFailsFastInsteadOfLooping() throws Exception {
        ToolContext context = context();
        Files.writeString(dir.resolve("a.txt"), "hello\n");
        tool("read_file").execute(Map.of("path", "a.txt"), context);
        ToolException failure = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(ToolException.class,
                () -> tool("edit_file").execute(Map.of("path", "a.txt", "old_string", "", "new_string", "X"), context)));
        assertTrue(failure.getMessage().contains("must not be empty"));
        assertEquals("hello\n", Files.readString(dir.resolve("a.txt")));
    }

    @Test
    void editRefusesFilesThatAreNotUtf8() throws Exception {
        ToolContext context = context();
        Files.write(dir.resolve("gbk.txt"), "你好，世界\n".getBytes(Charset.forName("GBK")));
        tool("read_file").execute(Map.of("path", "gbk.txt"), context);
        ToolException failure = assertThrows(ToolException.class,
                () -> tool("edit_file").execute(Map.of("path", "gbk.txt", "old_string", "x", "new_string", "y"), context));
        assertTrue(failure.getMessage().contains("not valid UTF-8"));
    }

    @Test
    void readFileCapsLongLinesAndReportsOffsetPastTheEnd() throws Exception {
        Files.writeString(dir.resolve("minified.js"), "x".repeat(100_000) + "\nsecond\r\nthird\rfourth");
        String text = tool("read_file").execute(Map.of("path", "minified.js"), context()).text();
        assertTrue(text.contains("[line truncated]"));
        assertTrue(text.length() < 3000, "超长的一行只显示开头");
        assertTrue(text.contains("     2\tsecond") && text.contains("     3\tthird") && text.contains("     4\tfourth"),
                "\\r\\n、\\r 都算换行，与 String.lines() 相同");
        String past = tool("read_file").execute(Map.of("path", "minified.js", "offset", 10), context()).text();
        assertTrue(past.contains("has 4 lines; offset 10 is past the end"));
    }

    @Test
    void fileToolsAreCheckedByTheirResolvedPath() throws Exception {
        PermissionPolicy policy = PermissionPolicy.defaults();
        assertEquals(Decision.ALLOW, decide(policy, "read_file", Map.of("path", "src/A.java")));
        assertEquals(Decision.ASK, decide(policy, "read_file", Map.of("path", "/etc/hostname")));
        assertEquals(Decision.ASK, decide(policy, "read_file", Map.of("path", "../outside.txt")));
        assertEquals(Decision.ASK, decide(policy, "read_file", Map.of("path", "~/.ssh/id_rsa")));
        Path outside = Files.createTempDirectory("outside");
        Files.createSymbolicLink(dir.resolve("link"), outside);
        assertEquals(Decision.ASK, decide(policy, "read_file", Map.of("path", "link/secret.txt")), "指向外面的符号链接也算外面");

        assertEquals(Decision.ALLOW, decide(policy, "glob", Map.of("pattern", "**/*.java")), "没传 path 时搜的是工作目录");
        assertEquals(Decision.ASK, decide(policy, "grep", Map.of("pattern", "password", "path", "/")));

        PermissionPolicy allowSrc = RulePermissionPolicy.of("default", List.of("write_file(src/*)", "edit_file(src/*)"), null, null);
        assertEquals(Decision.ALLOW, decide(allowSrc, "write_file", Map.of("path", "src/A.java", "content", "")));
        assertEquals(Decision.ASK, decide(allowSrc, "write_file", Map.of("path", "src/../../etc/passwd", "content", "")));
        assertEquals(Decision.ASK, decide(allowSrc, "edit_file", Map.of("path", "src/../../etc/passwd", "old_string", "a", "new_string", "b")));
    }

    Decision decide(PermissionPolicy policy, String toolName, Map<String, Object> args) {
        return policy.check(tool(toolName), args, context()).decision();
    }

    @Test
    void bashKeepsOnlyTheStartAndEndOfHugeOutput() throws Exception {
        String command = "head -c 2000000 /dev/zero | tr '\\0' a; echo; echo THE_END";
        String text = tool("bash").execute(Map.of("command", command), context()).text();
        assertTrue(text.contains("bytes of output omitted"));
        assertTrue(text.contains("THE_END"), "结尾保留着（报错通常在最后）");
        assertTrue(text.length() < 2 * ShellTools.KEPT_OUTPUT_BYTES + 1000);
    }

    @Test
    void grepStopsStoringResultsAfterMaxResults() throws Exception {
        Files.writeString(dir.resolve("many.txt"), "match line\n".repeat(500));
        String text = tool("grep").execute(Map.of("pattern", "match", "output", "content", "max_results", 10), context()).text();
        assertEquals(10, text.lines().filter(line -> line.contains("match line")).count());
        assertTrue(text.endsWith("... (490 more lines)"));
    }

    @Test
    void webFetchAsksByDefaultAndBlocksPrivateAddresses() throws Exception {
        Tool fetch = tool("web_fetch");
        assertTrue(fetch.readOnly(), "不修改状态，可以并行");
        assertEquals(PermissionLevel.ASK, fetch.defaultPermission(), "会把数据发到外面，默认要询问");

        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("169.254.169.254")), "云主机元数据地址");
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("10.1.2.3")));
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("192.168.0.1")));
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("100.64.0.1")));
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("::1")));
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("fd00::1")));
        assertFalse(WebTools.isPrivateAddress(InetAddress.getByName("93.184.216.34")));
        assertThrows(ToolException.class, () -> WebTools.requirePublicHost(URI.create("http://localhost:8080/")));
    }

    @Test
    void webFetchFollowsRedirectsOnlyWhenAllowedAndDecodesThePageCharset() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/old", exchange -> {
            exchange.getResponseHeaders().add("Location", "/page");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/page", exchange -> {
            byte[] body = "<html><head><meta charset=\"gbk\"><title>标题</title></head><body><p>中文内容</p></body></html>"
                    .getBytes(Charset.forName("GBK"));
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/old";
            ToolException blocked = assertThrows(ToolException.class, () -> tool("web_fetch").execute(Map.of("url", url), context()));
            assertTrue(blocked.getMessage().contains("private network"));

            String text = WebTools.webFetch(true).execute(Map.of("url", url), context()).text();
            assertTrue(text.contains("/page"), "跟随了重定向");
            assertTrue(text.contains("# 标题") && text.contains("中文内容"), "按页面里的 meta charset 解码 GBK");
        } finally {
            server.stop(0);
        }
    }
}
