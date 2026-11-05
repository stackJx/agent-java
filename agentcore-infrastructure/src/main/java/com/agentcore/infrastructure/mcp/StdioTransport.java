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

    private static final int MAX_STDERR_LINES = 30;

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
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(env);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        process = builder.start();
        stdin = process.getOutputStream();
        Thread.ofVirtual().name("mcp-stdout").start(() -> readMessages(onMessage, onClose));
        Thread.ofVirtual().name("mcp-stderr").start(this::collectStderr);
    }

    /** stdout 每行一条 JSON-RPC 消息（也可能是一批）；进程退出时通知客户端连接已断开。 */
    private void readMessages(Consumer<JsonNode> onMessage, Runnable onClose) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = Json.read(line);
                } catch (RuntimeException notJson) {
                    continue; // 非 JSON 行（某些服务器会往 stdout 打日志）
                }
                if (message.isArray()) {
                    message.forEach(onMessage);
                } else {
                    onMessage.accept(message);
                }
            }
        } catch (IOException ignored) {
            // 进程退出
        } finally {
            onClose.run();
        }
    }

    /** 只保留 stderr 的最后几行，出错时附在错误信息里帮助排查。 */
    private void collectStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    if (stderrTail.size() > MAX_STDERR_LINES) {
                        stderrTail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程退出
        }
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
