package com.agentcore.infrastructure.mcp;

import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** stdio 传输：本地子进程，按行收发 JSON-RPC。 */
public final class StdioTransport implements McpTransport {

    private final List<String> command;
    private final Map<String, String> env;
    private final Path cwd;
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private Process process;
    private OutputStream stdin;

    public StdioTransport(String command, List<String> args, Map<String, String> env, Path cwd) {
        this.command = new ArrayList<>();
        this.command.add(command);
        if (args != null) {
            this.command.addAll(args);
        }
        this.env = env == null ? Map.of() : env;
        this.cwd = cwd;
    }

    @Override
    public void start(Consumer<JsonNode> onMessage, Runnable onClose) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        process = pb.start();
        stdin = process.getOutputStream();
        Thread.ofVirtual().name("mcp-stdout").start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode msg;
                    try {
                        msg = Json.read(line);
                    } catch (RuntimeException e) {
                        continue; // 非 JSON 行（某些服务器会往 stdout 打日志）
                    }
                    if (msg.isArray()) {
                        msg.forEach(onMessage);
                    } else {
                        onMessage.accept(msg);
                    }
                }
            } catch (IOException ignored) {
                // 进程退出
            } finally {
                onClose.run();
            }
        });
        Thread.ofVirtual().name("mcp-stderr").start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        if (stderrTail.size() > 30) {
                            stderrTail.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // 进程退出
            }
        });
    }

    @Override
    public synchronized void send(JsonNode message) throws IOException {
        if (process == null || !process.isAlive()) {
            throw new McpException("MCP server process is not running" + diagnostics());
        }
        stdin.write((Json.write(message) + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    @Override
    public String diagnostics() {
        synchronized (stderrTail) {
            return stderrTail.isEmpty() ? "" : "\nstderr:\n" + String.join("\n", stderrTail);
        }
    }

    @Override
    public void close() {
        if (process == null) {
            return;
        }
        try {
            stdin.close();
        } catch (IOException ignored) {
            // 已关闭
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
