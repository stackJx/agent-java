package com.agentcore.domain.context.service;

import com.agentcore.domain.context.model.CompactionSettings;
import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.shared.cancel.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 上下文压缩（领域服务）：把旧对话总结成摘要后开启新的上下文。
 *
 * <p>为什么用「摘要 + 重新开始」而不是就地删改历史：历史只追加，prompt cache 前缀才能持续命中；
 * 新一代 Claude 会校验思考块所在的对话前缀，就地改写会让思考块失效甚至报错；
 * 摘要后的新上下文是一段全新的合法对话，对所有厂商都安全。
 */
public final class ConversationCompactor {

    public record Plan(List<Message> toSummarize, List<Message> keep) {
    }

    public record Summary(String text, TokenUsage usage) {
    }

    public static final String SUMMARY_SYSTEM = "You are a precise assistant that writes handoff summaries of agent work sessions.";

    public static final String SUMMARY_INSTRUCTIONS = """
            The conversation above is being compacted because the context window is filling up.
            Write a detailed handoff summary so that the work can continue seamlessly in a fresh context with no access to the original transcript.

            Include, in this order:
            1. User requests and intent: every explicit instruction and constraint from the user (quote key wording).
            2. Key technical context: decisions made, approaches chosen and rejected, important facts discovered.
            3. Files and resources: files read, created or modified, with the essential details or snippets needed to continue.
            4. Errors and fixes: problems encountered and how they were resolved (or not).
            5. Current state: what is done, what is in progress, and what remains.
            6. Next step: the immediate next action to take.

            Write the summary in the same language the user writes in. Output only the summary.""";

    public static final String CONTINUATION_NOTE = "This session continues from an earlier conversation that was compacted into the summary above. "
            + "Continue the work from where it left off without repeating completed steps.";

    private final CompactionSettings settings;

    public ConversationCompactor(CompactionSettings settings) {
        this.settings = settings == null ? CompactionSettings.defaults() : settings;
    }

    public CompactionSettings settings() {
        return settings;
    }

    public boolean shouldCompact(long estimatedTokens, int contextWindow) {
        return estimatedTokens >= settings.limitFor(contextWindow);
    }

    /** 最后一条是普通用户输入时原样保留它；否则（例如最后是工具结果）全部摘要 —— 工具结果不能脱离调用单独存在。 */
    public Plan plan(List<Message> messages) {
        if (!messages.isEmpty() && messages.get(messages.size() - 1).isPlainUserInput()) {
            return new Plan(messages.subList(0, messages.size() - 1), List.of(messages.get(messages.size() - 1)));
        }
        return new Plan(messages, List.of());
    }

    public Summary summarize(List<Message> messages, ModelGateway gateway) {
        return summarize(messages, gateway, ModelStreamListener.NOOP, CancellationToken.NONE);
    }

    /**
     * 生成摘要。引擎用这个版本：传入带重试的模型、只转发重试通知的 listener、本次运行的取消令牌 ——
     * 摘要可能要生成很久，期间用户点取消要能立刻停下。
     */
    public Summary summarize(List<Message> messages, ModelGateway gateway, ModelStreamListener listener, CancellationToken cancel) {
        String transcript = renderTranscript(messages, 2000, 8000);
        int budgetChars = settings.transcriptBudget() * 3;
        if (transcript.length() > budgetChars) {
            int head = (int) (budgetChars * 0.2);
            int tail = budgetChars - head;
            transcript = transcript.substring(0, head) + "\n\n...[middle of transcript omitted]...\n\n"
                    + transcript.substring(transcript.length() - tail);
        }
        ModelRequest req = new ModelRequest(SUMMARY_SYSTEM,
                List.of(Message.user("<transcript>\n" + transcript + "\n</transcript>\n\n" + SUMMARY_INSTRUCTIONS)),
                List.of(), settings.summaryMaxTokens(), Map.of());
        ModelResponse resp = gateway.stream(req, listener, cancel);
        String text = resp.message().text().strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("compaction failed: empty summary");
        }
        return new Summary(text, resp.usage());
    }

    public List<Message> buildCompactedHistory(String summary, List<Message> keep) {
        String head = "<conversation_summary>\n" + summary + "\n</conversation_summary>\n\n" + CONTINUATION_NOTE;
        List<Message> tail = new ArrayList<>(keep);
        if (!tail.isEmpty() && tail.get(0).isPlainUserInput()) {
            Message first = tail.remove(0);
            List<ContentBlock> merged = new ArrayList<>();
            merged.add(new TextBlock(head + "\n\nLatest user message:"));
            merged.addAll(first.content());
            tail.add(0, Message.user(merged));
            return tail;
        }
        tail.add(0, Message.user(head));
        return tail;
    }

    /** 渲染成纯文本记录（摘要请求用），最近 3 条消息保留更多工具输出。 */
    public static String renderTranscript(List<Message> messages, int resultChars, int recentResultChars) {
        StringBuilder transcript = new StringBuilder();
        int n = messages.size();
        for (int i = 0; i < n; i++) {
            Message message = messages.get(i);
            int limit = i >= n - 3 ? recentResultChars : resultChars;
            String role = message.role().wire();
            for (ContentBlock block : message.content()) {
                String line = switch (block) {
                    case TextBlock t when !t.text().isBlank() -> "[" + role + "]: " + t.text();
                    case ToolCall c -> "[assistant → tool " + c.name() + "]: "
                            + clip(c.rawArguments() != null ? c.rawArguments() : String.valueOf(c.arguments()), limit);
                    case ToolResult r -> "[" + (r.isError() ? "tool error" : "tool result")
                            + (r.toolName() != null ? " " + r.toolName() : "") + "]: " + middle(r.text(), limit);
                    case ImageBlock img -> "[" + role + "]: (image)";
                    default -> null;
                };
                if (line != null) {
                    if (!transcript.isEmpty()) {
                        transcript.append("\n\n");
                    }
                    transcript.append(line);
                }
            }
        }
        return transcript.toString();
    }

    private static String clip(String s, int limit) {
        return s.length() <= limit ? s : s.substring(0, limit);
    }

    private static String middle(String s, int limit) {
        if (s.length() <= limit) {
            return s;
        }
        int half = limit / 2;
        return s.substring(0, half) + "\n...[" + (s.length() - limit) + " chars omitted]...\n" + s.substring(s.length() - half);
    }
}
