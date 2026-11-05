package com.agentcore.infrastructure.mcp;

import com.agentcore.domain.tool.model.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 按配置并发启动多个 MCP 服务器并收集工具。单个服务器失败不影响其他服务器（错误记录在 errors 里）。 */
public final class McpServerManager implements AutoCloseable {

    private static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 30;

    private final Map<String, Map<String, Object>> configs;
    private final Path cwd;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final List<Tool> tools = new CopyOnWriteArrayList<>();

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
        List<Tool> sorted = new ArrayList<>(tools);
        sorted.sort(Comparator.comparing(Tool::name)); // 稳定顺序（prompt cache）
        return sorted;
    }

    /** 启动一个服务器（握手有超时）并取回工具列表；配置了 tools 白名单时只保留名单里的工具。 */
    private void boot(String name, Map<String, Object> config) {
        McpClient client = null;
        try {
            client = McpClient.fromConfig(name, config, cwd);
            long timeoutSeconds = Long.parseLong(String.valueOf(config.getOrDefault("startup_timeout", DEFAULT_STARTUP_TIMEOUT_SECONDS)));
            startWithin(client, name, timeoutSeconds);
            List<?> allowed = config.get("tools") instanceof List<?> list ? list : null;
            for (JsonNode spec : client.listTools()) {
                if (allowed == null || allowed.contains(spec.path("name").asText())) {
                    tools.add(new McpTool(client, spec));
                }
            }
            clients.put(name, client);
        } catch (Exception e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            errors.put(name, root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage());
            if (client != null) {
                client.close();
            }
        }
    }

    /** 握手放到单独的线程里，这样卡住的服务器也能按时放弃。 */
    private static void startWithin(McpClient client, String name, long timeoutSeconds) throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Thread.ofVirtual().name("mcp-init-" + name).start(() -> {
            try {
                client.start();
                started.complete(null);
            } catch (Throwable failure) {
                started.completeExceptionally(failure);
            }
        });
        started.get(timeoutSeconds, TimeUnit.SECONDS);
    }

    public Map<String, String> errors() {
        return Map.copyOf(errors);
    }

    public List<Tool> tools() {
        return List.copyOf(tools);
    }

    public String promptSection() {
        String parts = clients.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(entry -> !entry.getValue().instructions().isBlank())
                .map(entry -> "## " + entry.getKey() + "\n" + entry.getValue().instructions().strip())
                .collect(Collectors.joining("\n\n"));
        return parts.isEmpty() ? "" : "# MCP server instructions\n" + parts;
    }

    @Override
    public void close() {
        clients.values().forEach(McpClient::close);
    }
}
