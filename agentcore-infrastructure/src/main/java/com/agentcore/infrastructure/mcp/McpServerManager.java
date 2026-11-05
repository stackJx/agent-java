package com.agentcore.infrastructure.mcp;

import com.agentcore.domain.tool.model.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 按配置并发启动多个 MCP 服务器并收集工具。单个服务器失败不影响其他服务器（错误记录在 errors 里）。 */
public final class McpServerManager implements AutoCloseable {

    private final Map<String, Map<String, Object>> configs;
    private final Path cwd;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final List<Tool> tools = new CopyOnWriteArrayList<>();

    public McpServerManager(Map<String, Map<String, Object>> configs, Path cwd) {
        this.configs = new LinkedHashMap<>();
        if (configs != null) {
            configs.forEach((k, v) -> {
                if (!Boolean.FALSE.equals(v.get("enabled")) && !"false".equals(String.valueOf(v.get("enabled")))) {
                    this.configs.put(k, v);
                }
            });
        }
        this.cwd = cwd;
    }

    public List<Tool> start() {
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            configs.forEach((name, cfg) -> fs.add(ex.submit(() -> boot(name, cfg))));
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // boot 内部已记录错误
                }
            }
        }
        List<Tool> sorted = new ArrayList<>(tools);
        sorted.sort(Comparator.comparing(Tool::name)); // 稳定顺序（prompt cache）
        return sorted;
    }

    private void boot(String name, Map<String, Object> cfg) {
        McpClient client = null;
        try {
            client = McpClient.fromConfig(name, cfg, cwd);
            long startup = Long.parseLong(String.valueOf(cfg.getOrDefault("startup_timeout", 30)));
            McpClient c = client;
            java.util.concurrent.CompletableFuture<Void> init = new java.util.concurrent.CompletableFuture<>();
            Thread.ofVirtual().name("mcp-init-" + name).start(() -> {
                try {
                    c.start();
                    init.complete(null);
                } catch (Throwable t) {
                    init.completeExceptionally(t);
                }
            });
            init.get(startup, TimeUnit.SECONDS);
            List<JsonNode> specs = client.listTools();
            List<?> allow = cfg.get("tools") instanceof List<?> l ? l : null;
            for (JsonNode s : specs) {
                if (allow == null || allow.contains(s.path("name").asText())) {
                    tools.add(new McpTool(client, s));
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

    public Map<String, String> errors() {
        return Map.copyOf(errors);
    }

    public List<Tool> tools() {
        return List.copyOf(tools);
    }

    public String promptSection() {
        String parts = clients.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(e -> !e.getValue().instructions().isBlank())
                .map(e -> "## " + e.getKey() + "\n" + e.getValue().instructions().strip())
                .collect(Collectors.joining("\n\n"));
        return parts.isEmpty() ? "" : "# MCP server instructions\n" + parts;
    }

    @Override
    public void close() {
        clients.values().forEach(McpClient::close);
    }
}
