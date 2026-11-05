package com.agentcore.domain.context;

import com.agentcore.domain.conversation.ContentBlock;
import com.agentcore.domain.conversation.ImageBlock;
import com.agentcore.domain.conversation.Message;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.domain.conversation.ThinkingBlock;
import com.agentcore.domain.conversation.ToolCall;
import com.agentcore.domain.conversation.ToolResult;
import java.util.List;

/** 粗略 token 估算：ASCII 约 4 字符/token，CJK 等非 ASCII 约 1.2 字符/token，图片按 1600。 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long nonAscii = text.chars().filter(c -> c > 127).count();
        return (long) ((text.length() - nonAscii) / 4.0 + nonAscii / 1.2) + 1;
    }

    public static long estimate(Message m) {
        long total = 4;
        for (ContentBlock b : m.content()) {
            total += estimate(b);
        }
        return total;
    }

    public static long estimate(List<Message> messages) {
        return messages.stream().mapToLong(TokenEstimator::estimate).sum();
    }

    private static long estimate(ContentBlock b) {
        return switch (b) {
            case TextBlock t -> estimate(t.text());
            case ThinkingBlock t -> estimate(t.text());
            case ImageBlock i -> 1600;
            case ToolCall c -> estimate(c.rawArguments() != null ? c.rawArguments() : String.valueOf(c.arguments())) + 10;
            case ToolResult r -> r.content().stream().mapToLong(TokenEstimator::estimate).sum() + 10;
        };
    }
}
