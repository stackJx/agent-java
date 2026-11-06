package com.agentcore.infrastructure.mcp;

import com.agentcore.domain.tool.model.Tool;
import com.agentcore.infrastructure.json.Json;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * 按配置并发启动多个 MCP 服务器并收集工具。单个服务器失败不影响其他服务器（错误记录在 errors 里）。
 *
 * <p>协议本身（握手、分页、心跳、HTTP 会话）交给官方 MCP Java SDK；这里只负责按配置建连接、
 * 把远端工具包成 {@link McpTool}、收集各服务器的使用说明。
 *
 * <p>配置项（来自 {@code agentcore.mcp-servers.<名字>}）：
 * <ul>
 *   <li>子进程：{@code command}、{@code args}、{@code env}、{@code cwd}（相对于工作目录）；</li>
 *   <li>HTTP（Streamable HTTP）：{@code url}、{@code headers}；</li>
 *   <li>通用：{@code timeout}（单次请求，秒，默认 120）、{@code startup_timeout}（握手，秒，默认 30）、
 *       {@code tools}（只启用名单里的工具）、{@code trust_annotations}（信任服务器标的只读，默认 false）。</li>
 * </ul>
 * 工具名在所有服务器之间唯一：替换非法字符、截到 64 个字符后撞名的，加 {@code _2}、{@code _3} 后缀（按服务器名、工具名排序后分配，每次启动都一样）。
 */
public final class McpServerManager implements AutoCloseable {

    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 30;
    /** 启动失败时附在错误信息后面的 stderr 行数。 */
    private static final int STDERR_TAIL_LINES = 30;
    private static final McpJsonMapper JSON = new JacksonMcpJsonMapper(Json.MAPPER);

    private final Map<String, Map<String, Object>> configs;
    private final Path cwd;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    /** 各服务器启动时取回的工具（并行收集），start() 最后统一命名、包装成 McpTool。 */
    private final List<DiscoveredTool> discovered = new CopyOnWriteArrayList<>();
    private volatile List<Tool> tools = List.of();

    /** 从某个服务器取回的一个工具。 */
    private record DiscoveredTool(String server, McpSyncClient client, McpSchema.Tool spec, boolean trustAnnotations) {
    }

    public McpServerManager(Map<String, Map<String, Object>> configs, Path cwd) {
        this.configs = new LinkedHashMap<>();
        if (configs != null) {
            configs.forEach((name, config) -> {
                if (!Boolean.FALSE.equals(config.get("enabled")) && !"false".equals(String.valueOf(config.get("enabled")))) {
                    this.configs.put(name, config);
                }
            });
        }
        this.cwd = cwd;
    }

    /** 并行启动所有服务器；某个启动失败只记在 errors() 里，不影响其他服务器。返回全部工具（按名字排序）。 */
    public List<Tool> start() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> startups = new ArrayList<>();
            configs.forEach((name, config) -> startups.add(executor.submit(() -> boot(name, config))));
            for (Future<?> startup : startups) {
                try {
                    startup.get();
                } catch (Exception ignored) {
                    // boot 内部已记录错误
                }
            }
        }
        tools = nameUniquely(discovered);
        return tools;
    }

    /** 按（服务器名，工具名）排序后逐个命名：撞名的加数字后缀。返回按名字排序的工具（稳定顺序，利于 prompt cache）。 */
    private static List<Tool> nameUniquely(List<DiscoveredTool> found) {
        List<DiscoveredTool> ordered = new ArrayList<>(found);
        ordered.sort(Comparator.comparing(DiscoveredTool::server).thenComparing(item -> item.spec().name()));
        Set<String> taken = new HashSet<>();
        List<Tool> named = new ArrayList<>();
        for (DiscoveredTool item : ordered) {
            String name = uniqueName(McpTool.nameFor(item.server(), item.spec().name()), taken);
            named.add(new McpTool(item.server(), name, item.client(), item.spec(), item.trustAnnotations()));
        }
        named.sort(Comparator.comparing(Tool::name));
        return List.copyOf(named);
    }

    /** 截到长度上限；已经被占用就换成 "前缀_2"、"前缀_3"……（后缀也算在长度上限里）。 */
    static String uniqueName(String base, Set<String> taken) {
        String name = truncate(base, McpTool.MAX_NAME_LENGTH);
        for (int number = 2; taken.contains(name); number++) {
            String suffix = "_" + number;
            name = truncate(base, McpTool.MAX_NAME_LENGTH - suffix.length()) + suffix;
        }
        taken.add(name);
        return name;
    }

    private static String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    /** 连接一个服务器、完成握手并取回工具列表；配置了 tools 白名单时只保留名单里的工具。 */
    private void boot(String name, Map<String, Object> config) {
        Deque<String> stderr = new ArrayDeque<>();
        McpSyncClient client = null;
        try {
            client = McpClient.sync(transport(name, config, stderr))
                    .clientInfo(new McpSchema.Implementation("agentcore", "0.1.0"))
                    .requestTimeout(Duration.ofSeconds(seconds(config, "timeout", DEFAULT_REQUEST_TIMEOUT_SECONDS)))
                    .initializationTimeout(Duration.ofSeconds(seconds(config, "startup_timeout", DEFAULT_STARTUP_TIMEOUT_SECONDS)))
                    .build();
            client.initialize();
            List<?> allowed = config.get("tools") instanceof List<?> list ? list : null;
            boolean trustAnnotations = Boolean.parseBoolean(String.valueOf(config.getOrDefault("trust_annotations", false)));
            for (McpSchema.Tool spec : listAllTools(client)) {
                if (allowed == null || allowed.contains(spec.name())) {
                    discovered.add(new DiscoveredTool(name, client, spec, trustAnnotations));
                }
            }
            clients.put(name, client);
        } catch (Exception e) {
            errors.put(name, describe(e) + stderrTail(stderr));
            if (client != null) {
                client.close();
            }
        }
    }

    /** tools/list 是分页的：带着 nextCursor 一直取到最后一页。没声明 tools 能力的服务器（例如只提供资源）没有工具。 */
    private static List<McpSchema.Tool> listAllTools(McpSyncClient client) {
        List<McpSchema.Tool> all = new ArrayList<>();
        McpSchema.ServerCapabilities capabilities = client.getServerCapabilities();
        if (capabilities == null || capabilities.tools() == null) {
            return all;
        }
        String cursor = null;
        do {
            McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
            all.addAll(page.tools());
            cursor = page.nextCursor();
        } while (cursor != null);
        return all;
    }

    private McpClientTransport transport(String name, Map<String, Object> config, Deque<String> stderr) {
        if (config.get("url") != null) {
            return httpTransport(String.valueOf(config.get("url")), stringMap(config.get("headers")));
        }
        if (config.get("command") != null) {
            List<String> args = config.get("args") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
            Path dir = config.get("cwd") != null ? cwd.resolve(String.valueOf(config.get("cwd"))) : cwd;
            return stdioTransport(String.valueOf(config.get("command")), args, stringMap(config.get("env")), dir, stderr);
        }
        throw new IllegalArgumentException("MCP server '" + name + "' needs either 'command' or 'url'");
    }

    /**
     * 子进程：SDK 不支持指定工作目录，这里覆盖它创建 ProcessBuilder 的方法补上；stderr 只保留最后几行。
     * 命令找不到时 SDK 不会立即报错，而是等到握手超时，所以先自己检查一遍。
     */
    private static McpClientTransport stdioTransport(String command, List<String> args, Map<String, String> env, Path dir,
                                                     Deque<String> stderr) {
        requireExecutable(command, env, dir);
        ServerParameters params = ServerParameters.builder(command).args(args).env(env).build();
        StdioClientTransport transport = new StdioClientTransport(params, JSON) {
            @Override
            protected ProcessBuilder getProcessBuilder() {
                ProcessBuilder builder = super.getProcessBuilder();
                return dir == null ? builder : builder.directory(dir.toFile());
            }
        };
        transport.setStdErrorHandler(line -> {
            synchronized (stderr) {
                stderr.addLast(line);
                if (stderr.size() > STDERR_TAIL_LINES) {
                    stderr.removeFirst();
                }
            }
        });
        return transport;
    }

    /** 命令能否执行：带路径的看文件本身，否则在 PATH（配置的 env 优先）里找。 */
    private static void requireExecutable(String command, Map<String, String> env, Path dir) {
        if (command.contains("/") || command.contains("\\")) {
            Path file = dir == null ? Path.of(command) : dir.resolve(command);
            if (!Files.isExecutable(file)) {
                throw new IllegalArgumentException("command not found or not executable: " + command);
            }
            return;
        }
        String path = env.getOrDefault("PATH", System.getenv("PATH"));
        boolean found = path != null && Arrays.stream(path.split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .anyMatch(entry -> Files.isExecutable(Path.of(entry, command)) || Files.isExecutable(Path.of(entry, command + ".exe")));
        if (!found) {
            throw new IllegalArgumentException("command not found on PATH: " + command);
        }
    }

    /** Streamable HTTP：配置里的 url 拆成"服务器地址 + 路径"，自定义请求头加到每个请求上。 */
    private static McpClientTransport httpTransport(String url, Map<String, String> headers) {
        URI uri = URI.create(url);
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return HttpClientStreamableHttpTransport.builder(base)
                .endpoint(endpoint)
                .jsonMapper(JSON)
                .httpRequestCustomizer((request, method, target, body, context) -> headers.forEach(request::header))
                .build();
    }

    public Map<String, String> errors() {
        return Map.copyOf(errors);
    }

    public List<Tool> tools() {
        return tools;
    }

    /** 各服务器在握手时给出的使用说明，放进系统提示词。 */
    public String promptSection() {
        String parts = clients.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(entry -> instructions(entry.getValue()) != null)
                .map(entry -> "## " + entry.getKey() + "\n" + instructions(entry.getValue()))
                .collect(Collectors.joining("\n\n"));
        return parts.isEmpty() ? "" : "# MCP server instructions\n" + parts;
    }

    private static String instructions(McpSyncClient client) {
        String text = client.getServerInstructions();
        return text == null || text.isBlank() ? null : text.strip();
    }

    @Override
    public void close() {
        clients.values().forEach(McpSyncClient::closeGracefully);
    }

    // ------------------------------------------------------------------ 工具方法

    private static long seconds(Map<String, Object> config, String key, int fallback) {
        return Long.parseLong(String.valueOf(config.getOrDefault(key, fallback)));
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> map = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((key, item) -> map.put(String.valueOf(key), String.valueOf(item)));
        }
        return map;
    }

    /** SDK 的异常常常层层包装，取最底层的原因。 */
    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static String stderrTail(Deque<String> stderr) {
        synchronized (stderr) {
            return stderr.isEmpty() ? "" : "\nserver stderr:\n" + String.join("\n", stderr);
        }
    }
}
