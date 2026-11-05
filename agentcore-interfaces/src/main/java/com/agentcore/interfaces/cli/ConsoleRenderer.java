package com.agentcore.interfaces.cli;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.event.AgentEventListener;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.shared.util.Texts;
import java.io.PrintStream;
import java.util.List;

/** 把 Agent 事件渲染到终端：正文写 stdout，过程信息写 stderr。 */
public final class ConsoleRenderer implements AgentEventListener {

    private final PrintStream out;
    private final PrintStream info;
    private final Ansi a;
    private final boolean showThinking;
    private String mode; // text / thinking / null

    public ConsoleRenderer(PrintStream out, PrintStream info, Ansi ansi, boolean showThinking) {
        this.out = out;
        this.info = info;
        this.a = ansi;
        this.showThinking = showThinking;
    }

    private void switchTo(String next) {
        if (mode != null && !mode.equals(next)) {
            (mode.equals("text") ? out : info).println();
        }
        mode = next;
    }

    public static String tokens(long n) {
        return n >= 1000 ? String.format("%.1fk", n / 1000.0) : String.valueOf(n);
    }

    @Override
    public void onEvent(AgentEvent e) {
        switch (e) {
            case AgentEvent.TextDelta t -> {
                switchTo("text");
                out.print(t.text());
                out.flush();
            }
            case AgentEvent.ThinkingDelta t -> {
                if (showThinking) {
                    if (!"thinking".equals(mode)) {
                        switchTo("thinking");
                        info.print(a.dim("✻ "));
                    }
                    info.print(a.dim(t.text()));
                    info.flush();
                }
            }
            case AgentEvent.ToolStarted t -> {
                switchTo(null);
                info.println();
                info.println(a.cyan("⏺") + " " + a.bold(Texts.clip(t.summary(), 160)));
            }
            case AgentEvent.ToolProgress p -> info.println(a.dim("  ⎿ " + Texts.clip(p.message(), 160)));
            case AgentEvent.ToolFinished f -> {
                switchTo(null);
                List<String> lines = f.output().strip().lines().toList();
                if (lines.isEmpty()) {
                    lines = List.of("(no output)");
                }
                StringBuilder sb = new StringBuilder("  ⎿ ");
                for (int i = 0; i < Math.min(3, lines.size()); i++) {
                    sb.append(i > 0 ? "\n    " : "").append(Texts.clip(lines.get(i), 160));
                }
                if (lines.size() > 3) {
                    sb.append(" … (+").append(lines.size() - 3).append(" lines)");
                }
                sb.append(String.format("  [%.1fs]", f.durationMillis() / 1000.0));
                info.println(f.isError() ? a.red(sb.toString()) : a.dim(sb.toString()));
            }
            case AgentEvent.PermissionDecided p -> {
                if (p.decision().equals("deny")) {
                    info.println(a.yellow("  ✗ " + p.tool() + ": " + p.reason()));
                }
            }
            case AgentEvent.Compacted c -> {
                switchTo(null);
                info.println(a.yellow("\n✂ 上下文已压缩：~" + tokens(c.tokensBefore()) + " → ~" + tokens(c.tokensAfter()) + " tokens"));
            }
            case AgentEvent.Retrying r -> {
                switchTo(null);
                info.println(a.yellow("\n↻ 第 " + r.attempt() + " 次重试（" + r.delayMillis() / 1000.0 + "s 后）：" + Texts.clip(r.error(), 200)));
            }
            case AgentEvent.Notice n -> {
                switchTo(null);
                String msg = "\n[" + n.level() + "] " + n.message();
                info.println(n.level().equals("error") ? a.red(msg) : n.level().equals("warning") ? a.yellow(msg) : a.dim(msg));
            }
            case AgentEvent.RunFinished f -> {
                switchTo(null);
                TokenUsage u = f.usage();
                String cache = u.cacheReadTokens() > 0 ? "（缓存命中 " + tokens(u.cacheReadTokens()) + "）" : "";
                info.println(a.dim("\n[" + f.stopReason().wire() + " · " + f.turns() + " 轮 · 输入 " + tokens(u.totalInput()) + cache
                        + " · 输出 " + tokens(u.outputTokens()) + "]"));
            }
            default -> { }
        }
        info.flush();
    }
}
