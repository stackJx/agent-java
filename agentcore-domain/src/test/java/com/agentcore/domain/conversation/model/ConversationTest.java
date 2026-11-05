package com.agentcore.domain.conversation.model;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.conversation.event.ConversationStarted;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.domain.shared.exception.DomainException;
import com.agentcore.domain.shared.model.DomainEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationTest {

    private static Message assistantCalling(ToolCall... calls) {
        return Message.assistant(List.of(calls), null);
    }

    @Test
    void enforcesToolCallResultPairing() {
        Conversation c = Conversation.start();
        c.addUserInput(List.of(new TextBlock("hi")));
        ToolCall a = ToolCall.of("a", "read", Map.of());
        ToolCall b = ToolCall.of("b", "read", Map.of());
        c.addAssistantReply(assistantCalling(a, b), TokenUsage.of(10, 5));

        // 有未回复的工具调用时，不能追加新的用户输入或助手回复
        assertThrows(DomainException.class, () -> c.addUserInput(List.of(new TextBlock("again"))));
        assertThrows(DomainException.class, () -> c.addAssistantReply(Message.assistant("x"), null));
        // 结果必须与调用一一对应
        assertThrows(DomainException.class, () -> c.answerToolCalls(List.of(ToolResult.text("a", "read", "ok", false)), List.of()));

        // 顺序无关，存储时按调用顺序
        c.answerToolCalls(List.of(ToolResult.text("b", "read", "B", false), ToolResult.text("a", "read", "A", false)),
                List.of(new TextBlock("顺便看看 c")));
        Message results = c.messages().get(2);
        assertEquals(List.of("a", "b"), results.toolResults().stream().map(ToolResult::toolCallId).toList());
        assertEquals("顺便看看 c", results.text());
        assertTrue(c.pendingToolCalls().isEmpty());
    }

    @Test
    void repairsDanglingToolCallsAndReplaysFromEvents() {
        Conversation c = Conversation.start();
        List<DomainEvent> log = new ArrayList<>(c.pullEvents());
        c.addUserInput(List.of(new TextBlock("任务")));
        c.addAssistantReply(assistantCalling(ToolCall.of("x", "bash", Map.of("command", "ls"))), TokenUsage.of(100, 20));
        assertTrue(c.repairDanglingToolCalls("interrupted"));
        assertTrue(c.messages().get(2).toolResults().get(0).isError());
        c.recordUsage(TokenUsage.of(7, 3), "compaction");
        c.putMetadata("channel", "web");
        log.addAll(c.pullEvents());

        assertInstanceOf(ConversationStarted.class, log.get(0));
        Conversation replayed = Conversation.replay(log);
        assertEquals(c.messages(), replayed.messages());
        assertEquals(c.usage(), replayed.usage());
        assertEquals("任务", replayed.title());
        assertEquals("web", replayed.metadata().get("channel"));
        assertEquals(new Conversation.ContextSnapshot(2, 120), replayed.contextSnapshot());
    }

    @Test
    void compactionReplacesHistoryWithValidSequence() {
        Conversation c = Conversation.start();
        c.addUserInput(List.of(new TextBlock("a")));
        c.addAssistantReply(Message.assistant("b"), TokenUsage.of(1, 1));
        assertThrows(DomainException.class, () -> c.compact("s", List.of(Message.assistant("x"))));
        c.compact("summary", List.of(Message.user("<summary>")));
        assertEquals("a", c.title(), "压缩后标题保持为第一条用户输入");
        assertEquals(1, c.size());
        assertEquals(1, c.compactions());
        assertNull(c.contextSnapshot());
    }
}
