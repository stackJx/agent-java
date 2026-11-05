package com.agentcore.interfaces.cli;

import com.agentcore.application.AgentRuntime;
import com.agentcore.application.AgentService;
import com.agentcore.domain.agent.AgentEvent;
import com.agentcore.domain.agent.RunResult;
import com.agentcore.domain.conversation.Conversation;
import com.agentcore.domain.conversation.ConversationSummary;
import com.agentcore.domain.conversation.TokenUsage;
import com.agentcore.domain.memory.MemoryEntry;
import com.agentcore.domain.tool.Tool;
import java.util.Map;
import java.util.Optional;

/** 交互式命令行。 */
public final class ChatRepl {

    private static final String HELP = """
            命令：
              /help            显示帮助
              /new, /clear     开始新会话
              /compact         立即压缩上下文
              /tools           列出可用工具
              /usage           本会话 token 用量
              /sessions        列出历史会话
              /resume <id>     切换到某个历史会话
              /memory          查看长期记忆
              /exit            退出
            多行输入：行尾加 \\ 继续；运行中按 Ctrl+C 取消当前任务。""";

    private final AgentRuntime runtime;
    private final AgentService service;
    private final ConsoleIO io;
    private final Ansi a;
    private final ConsoleRenderer renderer;
    private String conversationId;

    public ChatRepl(AgentRuntime runtime, ConsoleIO io, Ansi ansi, boolean showThinking, String conversationId) {
        this.runtime = runtime;
        this.service = runtime.service();
        this.io = io;
        this.a = ansi;
        this.renderer = new ConsoleRenderer(io.out(), io.err(), ansi, showThinking);
        this.conversationId = conversationId;
    }

    public int run() {
        Conversation c = service.find(conversationId).orElseThrow();
        io.err().println(a.bold("agentcore · " + runtime.engine().model().describe() + " · 会话 " + conversationId));
        runtime.warnings().forEach((k, v) -> io.err().println(a.yellow(k + "：" + v)));
        if (c.size() > 0) {
            io.err().println(a.dim("已恢复 " + c.size() + " 条历史消息：" + c.title()));
        }
        io.err().println(a.dim("输入 /help 查看命令"));
        while (true) {
            String line = io.readLine(a.green("\n> "));
            if (line == null) {
                break;
            }
            StringBuilder sb = new StringBuilder(line);
            while (sb.toString().endsWith("\\")) {
                sb.setLength(sb.length() - 1);
                String more = io.readLine("… ");
                if (more == null) {
                    break;
                }
                sb.append('\n').append(more);
            }
            String text = sb.toString().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("/")) {
                if (!command(text)) {
                    break;
                }
                continue;
            }
            runOnce(text);
        }
        return 0;
    }

    private void runOnce(String text) {
        Object previous = SignalHandling.onInterrupt(() -> service.cancel(conversationId));
        try {
            RunResult r = service.chat(conversationId, text, renderer);
            if (r.error() != null) {
                io.err().println(a.red("运行失败：" + r.error()));
            }
        } finally {
            SignalHandling.restore(previous);
        }
    }

    /** 返回 false 表示退出。 */
    private boolean command(String text) {
        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1].strip() : "";
        switch (cmd) {
            case "/exit", "/quit" -> {
                return false;
            }
            case "/help" -> io.err().println(HELP);
            case "/new", "/clear" -> {
                conversationId = service.startConversation(Map.of()).id().value();
                io.err().println(a.dim("新会话 " + conversationId));
            }
            case "/compact" -> {
                Optional<AgentEvent.Compacted> c = service.compact(conversationId);
                io.err().println(a.dim(c.map(x -> "已压缩：~" + ConsoleRenderer.tokens(x.tokensBefore()) + " → ~"
                        + ConsoleRenderer.tokens(x.tokensAfter())).orElse("无需压缩")));
            }
            case "/tools" -> {
                for (Tool t : runtime.engine().tools()) {
                    String first = t.description().lines().findFirst().orElse("");
                    io.err().printf("  %-32s %s  %s%n", t.name(), t.readOnly() ? "只读" : "    ",
                            first.length() > 70 ? first.substring(0, 70) : first);
                }
            }
            case "/usage" -> {
                TokenUsage u = service.find(conversationId).map(Conversation::usage).orElse(TokenUsage.ZERO);
                io.err().println("  请求 " + u.requests() + " 次 · 输入 " + ConsoleRenderer.tokens(u.totalInput()) + "（缓存命中 "
                        + ConsoleRenderer.tokens(u.cacheReadTokens()) + "） · 输出 " + ConsoleRenderer.tokens(u.outputTokens())
                        + " · 当前上下文约 " + ConsoleRenderer.tokens(service.estimateContextTokens(conversationId)));
            }
            case "/sessions" -> {
                for (ConversationSummary s : service.list(20)) {
                    io.err().printf("  %s  %4d 条  %s%n", s.id(), s.messageCount(), s.title());
                }
            }
            case "/resume" -> {
                if (arg.isEmpty() || service.find(arg).isEmpty()) {
                    io.err().println(a.yellow("找不到会话 " + arg));
                } else {
                    conversationId = arg;
                    io.err().println(a.dim("已切换到会话 " + arg + "（" + service.find(arg).get().size() + " 条消息）"));
                }
            }
            case "/memory" -> {
                if (runtime.memory() != null) {
                    for (MemoryEntry m : runtime.memory().list()) {
                        io.err().println("  " + m.name() + " [" + m.type().wire() + "] " + m.description());
                    }
                }
            }
            default -> io.err().println(a.yellow("未知命令 " + cmd + "，输入 /help 查看"));
        }
        return true;
    }
}
