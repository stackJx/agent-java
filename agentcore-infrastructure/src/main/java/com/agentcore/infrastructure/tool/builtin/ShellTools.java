package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** bash 工具：在工作目录执行命令，返回合并的 stdout/stderr 与退出码；超时或取消时杀掉整个进程树。 */
public final class ShellTools {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_TIMEOUT_SECONDS = 600;

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

    /**
     * 用 bash -c 跑命令：标准输出和错误合并；分页器关掉、stdin 接 /dev/null，防止命令卡住等输入。
     * 超时或取消时连同子进程一起杀掉。
     */
    static ToolOutput run(Map<String, Object> args, ToolContext ctx) throws Exception {
        String command = (String) args.get("command");
        int timeoutSeconds = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, ToolArgs.integer(args, "timeout", DEFAULT_TIMEOUT_SECONDS)));
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", command).directory(ctx.cwd().toFile()).redirectErrorStream(true);
        builder.environment().put("PAGER", "cat");
        builder.environment().put("GIT_PAGER", "cat");
        builder.environment().put("TERM", "dumb");
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> copy(process.getInputStream(), output));
        Runnable unregister = ctx.cancel().onCancel(() -> kill(process));
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                kill(process);
                reader.join(2000);
                throw new ToolException("Command timed out after " + timeoutSeconds + "s and was killed.\n" + text(output));
            }
            reader.join(5000);
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        } finally {
            unregister.run();
        }
        String text = text(output).stripTrailing();
        int exitCode = process.exitValue();
        return ToolOutput.text((text.isEmpty() ? "(no output)" : text) + "\n[exit code " + exitCode + "]", Map.of("exit_code", exitCode));
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                synchronized (out) {
                    out.write(buffer, 0, read);
                }
            }
        } catch (IOException ignored) {
            // 进程被杀时流会关闭
        }
    }

    private static String text(ByteArrayOutputStream output) {
        synchronized (output) {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
