package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.FunctionTool;
import com.agentcore.domain.tool.Schemas;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolContext;
import com.agentcore.domain.tool.ToolException;
import com.agentcore.domain.tool.ToolOutput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** bash 工具：在工作目录执行命令，返回合并的 stdout/stderr 与退出码；超时或取消时杀掉整个进程树。 */
public final class ShellTools {

    private ShellTools() {
    }

    public static Tool bash() {
        return FunctionTool.builder("bash")
                .description("Run a shell command in the working directory and return its combined stdout/stderr and exit code. "
                        + "Each call runs in a fresh shell (cd / exported variables do not persist between calls).")
                .schema(Schemas.object()
                        .string("command", "The bash command to run.", true)
                        .integer("timeout", "Timeout in seconds (default 120, max 600).", false)
                        .build())
                .timeout(null) // 自己控制超时
                .handler(ShellTools::run)
                .build();
    }

    static ToolOutput run(Map<String, Object> a, ToolContext ctx) throws Exception {
        String command = (String) a.get("command");
        int timeout = Math.max(1, Math.min(600, a.get("timeout") instanceof Number n ? n.intValue() : 120));
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command).directory(ctx.cwd().toFile()).redirectErrorStream(true);
        pb.environment().put("PAGER", "cat");
        pb.environment().put("GIT_PAGER", "cat");
        pb.environment().put("TERM", "dumb");
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        Process proc = pb.start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> copy(proc.getInputStream(), buf));
        Runnable unregister = ctx.cancel().onCancel(() -> kill(proc));
        try {
            if (!proc.waitFor(timeout, TimeUnit.SECONDS)) {
                kill(proc);
                reader.join(2000);
                throw new ToolException("Command timed out after " + timeout + "s and was killed.\n" + text(buf));
            }
            reader.join(5000);
        } catch (InterruptedException e) {
            kill(proc);
            throw e;
        } finally {
            unregister.run();
        }
        String out = text(buf).stripTrailing();
        int code = proc.exitValue();
        return ToolOutput.text((out.isEmpty() ? "(no output)" : out) + "\n[exit code " + code + "]", Map.of("exit_code", code));
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] b = new byte[8192];
        try {
            int n;
            while ((n = in.read(b)) >= 0) {
                synchronized (out) {
                    out.write(b, 0, n);
                }
            }
        } catch (IOException ignored) {
            // 进程被杀时流会关闭
        }
    }

    private static String text(ByteArrayOutputStream buf) {
        synchronized (buf) {
            return buf.toString(StandardCharsets.UTF_8);
        }
    }
}
