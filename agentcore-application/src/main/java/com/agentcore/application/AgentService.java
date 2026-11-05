package com.agentcore.application;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.event.AgentEventListener;
import com.agentcore.domain.agent.model.RunControl;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.agent.service.AgentEngine;
import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.conversation.model.ConversationSummary;
import com.agentcore.domain.conversation.repository.ConversationRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用服务：对外暴露的用例（开始会话、发送消息、取消、插话、压缩、查询）。
 *
 * <p>负责用例级的编排与约束 —— 加载/保存聚合、同一会话同一时刻只允许一个运行、管理运行句柄；
 * 具体的 Agent 循环规则在领域服务 {@link AgentEngine} 里。
 */
public final class AgentService {

    /** 正在运行的一次任务：可取消、可插话、可等待结果。 */
    public record RunHandle(String conversationId, RunControl control, CompletableFuture<RunResult> result) {
        public void cancel() {
            control.cancel();
        }

        public void steer(String text) {
            control.steer(text);
        }
    }

    private final AgentEngine engine;
    private final ConversationRepository repository;
    /** 正在运行的会话 → 它的控制句柄。既用来保证"一个会话同时只有一个运行"，也用来转发取消和插话。 */
    private final Map<String, RunControl> running = new ConcurrentHashMap<>();

    public AgentService(AgentEngine engine, ConversationRepository repository) {
        this.engine = engine;
        this.repository = repository;
    }

    public AgentEngine engine() {
        return engine;
    }

    public ConversationRepository repository() {
        return repository;
    }

    // ------------------------------------------------------------------ 命令

    /** 新建会话。元数据在创建时一次写全（进入 ConversationStarted 事件），调用方传入的同名键优先。 */
    public Conversation startConversation(Map<String, String> metadata) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("model", engine.model().describe());
        merged.put("agent", engine.definition().name());
        merged.put("cwd", String.valueOf(engine.definition().settings().cwd()));
        if (metadata != null) {
            merged.putAll(metadata);
        }
        Conversation conversation = Conversation.start(ConversationId.newId(), merged);
        repository.save(conversation);
        return conversation;
    }

    /** 同步执行：阻塞直到运行结束。 */
    public RunResult chat(String conversationId, String text, AgentEventListener listener) {
        return chat(conversationId, List.of(new TextBlock(text)), listener, new RunControl());
    }

    public RunResult chat(String conversationId, List<? extends ContentBlock> input, AgentEventListener listener, RunControl control) {
        Conversation conversation = load(conversationId);
        claim(conversationId, control);
        try {
            return engine.run(conversation, input, listener, control);
        } finally {
            running.remove(conversationId, control);
        }
    }

    /** 异步执行（虚拟线程）：适合 Web 服务。 */
    public RunHandle chatAsync(String conversationId, List<? extends ContentBlock> input, AgentEventListener listener) {
        Conversation conversation = load(conversationId);
        RunControl control = new RunControl();
        claim(conversationId, control);
        // 每个运行一个虚拟线程：运行大部分时间在等模型和工具，虚拟线程几乎不占资源
        CompletableFuture<RunResult> future = new CompletableFuture<>();
        Thread.ofVirtual().name("agent-run-" + conversationId).start(() -> {
            try {
                future.complete(engine.run(conversation, input, listener, control));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                running.remove(conversationId, control);
            }
        });
        return new RunHandle(conversationId, control, future);
    }

    /**
     * 占住会话。同一会话已有运行时直接拒绝而不是排队：
     * 两个运行同时往一段历史里写，必然破坏工具调用的一一对应。
     */
    private void claim(String conversationId, RunControl control) {
        if (running.putIfAbsent(conversationId, control) != null) {
            throw new IllegalStateException("conversation " + conversationId + " already has a running task");
        }
    }

    public boolean cancel(String conversationId) {
        RunControl control = running.get(conversationId);
        if (control == null) {
            return false;
        }
        control.cancel();
        return true;
    }

    public boolean steer(String conversationId, String text) {
        RunControl control = running.get(conversationId);
        if (control == null) {
            return false;
        }
        control.steer(text);
        return true;
    }

    public Optional<AgentEvent.Compacted> compact(String conversationId) {
        if (running.containsKey(conversationId)) {
            throw new IllegalStateException("cannot compact while a task is running");
        }
        return engine.compact(load(conversationId), null);
    }

    // ------------------------------------------------------------------ 查询

    public Optional<Conversation> find(String conversationId) {
        return repository.findById(ConversationId.of(conversationId));
    }

    public Optional<Conversation> latest() {
        return repository.findLatest();
    }

    public List<ConversationSummary> list(int limit) {
        return repository.list(limit);
    }

    public boolean isRunning(String conversationId) {
        return running.containsKey(conversationId);
    }

    public long estimateContextTokens(String conversationId) {
        return engine.estimateContextTokens(load(conversationId));
    }

    private Conversation load(String conversationId) {
        return repository.findById(ConversationId.of(conversationId))
                .orElseThrow(() -> new IllegalArgumentException("conversation not found: " + conversationId));
    }
}
