package com.agentcore.interfaces.cli;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.event.AgentEventListener;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.shared.util.Texts;
import java.io.PrintStream;
import java.util.List;

/** 把 Agent 事件渲染到终端：正文写 stdout，过程信息写 stderr。 */
public final class ConsoleRenderer implements AgentEventListener {

    /** 工具输出预览最多显示几行。 */
    private static final int PREVIEW_LINES = 3;
    private static final int MAX_LINE_CHARS = 160;

    /** 当前正在往哪个流里连续输出，切换时先换行，避免正文和思考挤在同一行。 */
    private enum Mode { TEXT, THINKING }

    private final PrintStream out;
    private final PrintStream info;
    private final Ansi ansi;
    private final boolean showThinking;
    private Mode mode; // null 表示刚输出完一整行

    public ConsoleRenderer(PrintStream out, PrintStream info, Ansi ansi, boolean showThinking) {
        this.out = out;
        this.info = info;
        this.ansi = ansi;
        this.showThinking = showThinking;
    }

    private void switchTo(Mode next) {
        if (mode != null && mode != next) {
            (mode == Mode.TEXT ? out : info).println();
        }
        mode = next;
    }

    /** 1234 → "1.2k"。 */
    public static String tokens(long count) {
        return count >= 1000 ? String.format("%.1fk", count / 1000.0) : String.valueOf(count);
    }

    @Override
    public void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.TextDelta delta -> {
                switchTo(Mode.TEXT);
                out.print(delta.text());
                out.flush();
            }
            case AgentEvent.ThinkingDelta delta -> {
                if (showThinking) {
                    if (mode != Mode.THINKING) {
                        switchTo(Mode.THINKING);
                        info.print(ansi.dim("✻ "));
                    }
                    info.print(ansi.dim(delta.text()));
                    info.flush();
                }
            }
            case AgentEvent.ToolStarted started -> {
                switchTo(null);
                info.println();
                info.println(ansi.cyan("⏺") + " " + ansi.bold(Texts.clip(started.summary(), MAX_LINE_CHARS)));
            }
            case AgentEvent.ToolProgress progress -> info.println(ansi.dim("  ⎿ " + Texts.clip(progress.message(), MAX_LINE_CHARS)));
            case AgentEvent.ToolFinished finished -> {
                switchTo(null);
                String preview = toolPreview(finished);
                info.println(finished.isError() ? ansi.red(preview) : ansi.dim(preview));
            }
            case AgentEvent.PermissionDecided decided -> {
                if (decided.decision().equals("deny")) {
                    info.println(ansi.yellow("  ✗ " + decided.tool() + ": " + decided.reason()));
                }
            }
            case AgentEvent.Compacted compacted -> {
                switchTo(null);
                info.println(ansi.yellow("\n✂ 上下文已压缩：~" + tokens(compacted.tokensBefore()) + " → ~" + tokens(compacted.tokensAfter())
                        + " tokens"));
            }
            case AgentEvent.Retrying retrying -> {
                switchTo(null);
                // 重试会从头重新生成这一轮：上面已经打印出来的半截回复作废
                info.println(ansi.yellow("\n↻ 第 " + retrying.attempt() + " 次重试（" + retrying.delayMillis() / 1000.0 + "s 后，"
                        + "上面这一轮已输出的内容作废，将重新生成）：" + Texts.clip(retrying.error(), 200)));
            }
            case AgentEvent.Notice notice -> {
                switchTo(null);
                String line = "\n[" + notice.level() + "] " + notice.message();
                info.println(switch (notice.level()) {
                    case "error" -> ansi.red(line);
                    case "warning" -> ansi.yellow(line);
                    default -> ansi.dim(line);
                });
            }
            case AgentEvent.RunFinished finished -> {
                switchTo(null);
                info.println(ansi.dim(runSummary(finished)));
            }
            default -> { }
        }
        info.flush();
    }

    /** 工具输出的前几行 + 用时，例如 "  ⎿ line1\n    line2 … (+12 lines)  [0.3s]"。 */
    private static String toolPreview(AgentEvent.ToolFinished finished) {
        List<String> lines = finished.output().strip().lines().toList();
        if (lines.isEmpty()) {
            lines = List.of("(no output)");
        }
        StringBuilder preview = new StringBuilder("  ⎿ ");
        for (int i = 0; i < Math.min(PREVIEW_LINES, lines.size()); i++) {
            preview.append(i > 0 ? "\n    " : "").append(Texts.clip(lines.get(i), MAX_LINE_CHARS));
        }
        if (lines.size() > PREVIEW_LINES) {
            preview.append(" … (+").append(lines.size() - PREVIEW_LINES).append(" lines)");
        }
        preview.append(String.format("  [%.1fs]", finished.durationMillis() / 1000.0));
        return preview.toString();
    }

    /** 例如 "[end_turn · 3 轮 · 输入 12.3k（缓存命中 10.0k） · 输出 1.2k]"。 */
    private static String runSummary(AgentEvent.RunFinished finished) {
        TokenUsage usage = finished.usage();
        String cache = usage.cacheReadTokens() > 0 ? "（缓存命中 " + tokens(usage.cacheReadTokens()) + "）" : "";
        return "\n[" + finished.stopReason().wire() + " · " + finished.turns() + " 轮 · 输入 " + tokens(usage.totalInput()) + cache
                + " · 输出 " + tokens(usage.outputTokens()) + "]";
    }
}
