package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * bash 工具：在工作目录执行命令，返回合并的 stdout/stderr 与退出码；超时或取消时杀掉整个进程树。
 * 输出只保留开头和结尾各 {@value #KEPT_OUTPUT_BYTES} 字节，输出再多也不会撑爆内存。
 */
public final class ShellTools {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    /** 输出开头、结尾各保留多少字节（之后截断拦截器还会再截到 maxOutputChars）。 */
    static final int KEPT_OUTPUT_BYTES = 256 * 1024;
    /** 命令结束后，等读取线程把剩下的输出读完的时间。后台进程（cmd &amp;）会一直占着输出管道，不能无限等。 */
    private static final long OUTPUT_DRAIN_MILLIS = 5000;
    private static final long OUTPUT_DRAIN_AFTER_KILL_MILLIS = 2000;

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
        HeadTailBuffer output = new HeadTailBuffer(KEPT_OUTPUT_BYTES, KEPT_OUTPUT_BYTES);
        Thread reader = Thread.ofVirtual().start(() -> output.readFully(process.getInputStream()));
        Runnable unregister = ctx.cancel().onCancel(() -> kill(process));
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                kill(process);
                reader.join(OUTPUT_DRAIN_AFTER_KILL_MILLIS);
                throw new ToolException("Command timed out after " + timeoutSeconds + "s and was killed.\n" + output.text());
            }
            reader.join(OUTPUT_DRAIN_MILLIS);
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        } finally {
            unregister.run();
        }
        String text = output.text().stripTrailing();
        int exitCode = process.exitValue();
        return ToolOutput.text((text.isEmpty() ? "(no output)" : text) + "\n[exit code " + exitCode + "]", Map.of("exit_code", exitCode));
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

}
