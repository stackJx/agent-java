package com.agentcore.interfaces.cli;

import com.agentcore.application.AgentService;
import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationSummary;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.memory.model.MemoryEntry;
import com.agentcore.domain.memory.repository.MemoryRepository;
import com.agentcore.domain.tool.model.Tool;
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

    private final AgentService service;
    private final MemoryRepository memory;
    private final Map<String, String> warnings;
    private final ConsoleIO io;
    private final Ansi ansi;
    private final ConsoleRenderer renderer;
    private String conversationId;

    /**
     * @param memory   长期记忆，未启用时为 null（/memory 命令用）
     * @param warnings 启动期的非致命问题（例如某个 MCP 服务器没起来），进入对话前显示
     */
    public ChatRepl(AgentService service, MemoryRepository memory, Map<String, String> warnings, ConsoleIO io, Ansi ansi,
                    boolean showThinking, String conversationId) {
        this.service = service;
        this.memory = memory;
        this.warnings = warnings == null ? Map.of() : warnings;
        this.io = io;
        this.ansi = ansi;
        this.renderer = new ConsoleRenderer(io.out(), io.err(), ansi, showThinking);
        this.conversationId = conversationId;
    }

    /** 读一条输入 → 斜杠命令就执行命令，否则交给 Agent 跑一次；直到 /exit 或输入结束（Ctrl+D）。 */
    public int run() {
        printBanner();
        while (true) {
            String text = readInput();
            if (text == null) {
                break;
            }
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

    private void printBanner() {
        Conversation conversation = service.find(conversationId).orElseThrow();
        io.err().println(ansi.bold("agentcore · " + service.engine().model().describe() + " · 会话 " + conversationId));
        warnings.forEach((source, problem) -> io.err().println(ansi.yellow(source + "：" + problem)));
        if (conversation.size() > 0) {
            io.err().println(ansi.dim("已恢复 " + conversation.size() + " 条历史消息：" + conversation.title()));
        }
        io.err().println(ansi.dim("输入 /help 查看命令"));
    }

    /** 读一条输入，行尾的 \ 表示续行。输入结束返回 null；在提示符下按 Ctrl+C 放弃这条输入（返回空串）。 */
    private String readInput() {
        try {
            String line = io.readLine(ansi.green("\n> "));
            if (line == null) {
                return null;
            }
            StringBuilder input = new StringBuilder(line);
            while (input.toString().endsWith("\\")) {
                input.setLength(input.length() - 1);
                String more = io.readLine("… ");
                if (more == null) {
                    break;
                }
                input.append('\n').append(more);
            }
            return input.toString().strip();
        } catch (ConsoleIO.InterruptedInput e) {
            io.err().println(ansi.dim("（已放弃这条输入，Ctrl+D 或 /exit 退出）"));
            return "";
        }
    }

    /** 跑一次；运行期间 Ctrl+C 只取消当前任务，不退出程序。 */
    private void runOnce(String text) {
        try (ConsoleIO.InterruptHandler cancelOnCtrlC = io.onInterrupt(() -> service.cancel(conversationId))) {
            RunResult result = service.chat(conversationId, text, renderer);
            if (result.error() != null) {
                io.err().println(ansi.red("运行失败：" + result.error()));
            }
        }
    }

    /** 返回 false 表示退出。 */
    private boolean command(String text) {
        String[] parts = text.split("\\s+", 2);
        String name = parts[0];
        String argument = parts.length > 1 ? parts[1].strip() : "";
        switch (name) {
            case "/exit", "/quit" -> {
                return false;
            }
            case "/help" -> io.err().println(HELP);
            case "/new", "/clear" -> {
                conversationId = service.startConversation(Map.of()).id().value();
                io.err().println(ansi.dim("新会话 " + conversationId));
            }
            case "/compact" -> compactNow();
            case "/tools" -> listTools();
            case "/usage" -> printUsage();
            case "/sessions" -> listSessions();
            case "/resume" -> resume(argument);
            case "/memory" -> listMemories();
            default -> io.err().println(ansi.yellow("未知命令 " + name + "，输入 /help 查看"));
        }
        return true;
    }

    private void compactNow() {
        Optional<AgentEvent.Compacted> compacted = service.compact(conversationId);
        io.err().println(ansi.dim(compacted.map(event -> "已压缩：~" + ConsoleRenderer.tokens(event.tokensBefore()) + " → ~"
                + ConsoleRenderer.tokens(event.tokensAfter())).orElse("无需压缩")));
    }

    private void listTools() {
        for (Tool tool : service.engine().tools()) {
            String firstLine = tool.description().lines().findFirst().orElse("");
            io.err().printf("  %-32s %s  %s%n", tool.name(), tool.readOnly() ? "只读" : "    ",
                    firstLine.length() > 70 ? firstLine.substring(0, 70) : firstLine);
        }
    }

    private void printUsage() {
        TokenUsage usage = service.find(conversationId).map(Conversation::usage).orElse(TokenUsage.ZERO);
        io.err().println("  请求 " + usage.requests() + " 次 · 输入 " + ConsoleRenderer.tokens(usage.totalInput()) + "（缓存命中 "
                + ConsoleRenderer.tokens(usage.cacheReadTokens()) + "） · 输出 " + ConsoleRenderer.tokens(usage.outputTokens())
                + " · 当前上下文约 " + ConsoleRenderer.tokens(service.estimateContextTokens(conversationId)));
    }

    private void listSessions() {
        for (ConversationSummary summary : service.list(20)) {
            io.err().printf("  %s  %4d 条  %s%n", summary.id(), summary.messageCount(), summary.title());
        }
    }

    private void resume(String id) {
        Optional<Conversation> target = id.isEmpty() ? Optional.empty() : service.find(id);
        if (target.isEmpty()) {
            io.err().println(ansi.yellow("找不到会话 " + id));
            return;
        }
        conversationId = id;
        io.err().println(ansi.dim("已切换到会话 " + id + "（" + target.get().size() + " 条消息）"));
    }

    private void listMemories() {
        if (memory == null) {
            return;
        }
        for (MemoryEntry entry : memory.list()) {
            io.err().println("  " + entry.name() + " [" + entry.type().wire() + "] " + entry.description());
        }
    }
}
