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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

        /** 插话；返回 false 表示没收下（运行已经结束，应该作为新消息发送）。 */
        public boolean steer(String text) {
            return control.steer(text);
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
        return startConversation(metadata, null);
    }

    /**
     * 新建属于 owner 的会话（多用户部署时由接口层传入当前用户；单用户时为 null）。
     * owner 最后写入，覆盖调用方在元数据里自己填的 owner —— 不能冒充别人建会话。
     */
    public Conversation startConversation(Map<String, String> metadata, String owner) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("model", engine.model().describe());
        merged.put("agent", engine.definition().name());
        merged.put("cwd", String.valueOf(engine.definition().settings().cwd()));
        if (metadata != null) {
            merged.putAll(metadata);
        }
        if (owner != null) {
            merged.put(Conversation.OWNER, owner);
        }
        Conversation conversation = Conversation.start(ConversationId.newId(), merged);
        repository.save(conversation);
        return conversation;
    }

    /** 同步执行：阻塞直到运行结束。 */
    public RunResult chat(String conversationId, String text, AgentEventListener listener) {
        return chat(conversationId, List.of(new TextBlock(text)), listener, new RunControl());
    }

    /** 先占住会话再加载：保证拿到的是上一次运行保存之后的最新状态。 */
    public RunResult chat(String conversationId, List<? extends ContentBlock> input, AgentEventListener listener, RunControl control) {
        claim(conversationId, control);
        try {
            return engine.run(load(conversationId), input, listener, control);
        } finally {
            release(conversationId, control);
        }
    }

    /** 异步执行（虚拟线程）：适合 Web 服务。 */
    public RunHandle chatAsync(String conversationId, List<? extends ContentBlock> input, AgentEventListener listener) {
        return chatAsync(conversationId, input, listener, () -> { });
    }

    /**
     * 异步执行。beforeStart 在占住会话之后、运行开始之前执行（例如挂上这次运行的审批通道）：
     * 会话已经在运行时直接抛 IllegalStateException，beforeStart 不会执行，不会影响正在进行的那次运行。
     */
    public RunHandle chatAsync(String conversationId, List<? extends ContentBlock> input, AgentEventListener listener,
                               Runnable beforeStart) {
        RunControl control = new RunControl();
        claim(conversationId, control);
        Conversation conversation;
        try {
            conversation = load(conversationId);
            beforeStart.run();
        } catch (RuntimeException e) {
            release(conversationId, control);
            throw e;
        }
        // 每个运行一个虚拟线程：运行大部分时间在等模型和工具，虚拟线程几乎不占资源
        CompletableFuture<RunResult> future = new CompletableFuture<>();
        Thread.ofVirtual().name("agent-run-" + conversationId).start(() -> {
            try {
                future.complete(engine.run(conversation, input, listener, control));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                release(conversationId, control);
            }
        });
        return new RunHandle(conversationId, control, future);
    }

    /**
     * 占住会话。同一会话已有运行（或正在手动压缩）时直接拒绝而不是排队：
     * 两个任务同时往一段历史里写，必然破坏工具调用的一一对应。
     */
    private void claim(String conversationId, RunControl control) {
        if (running.putIfAbsent(conversationId, control) != null) {
            throw new IllegalStateException("conversation " + conversationId + " already has a running task");
        }
    }

    private void release(String conversationId, RunControl control) {
        running.remove(conversationId, control);
    }

    public boolean cancel(String conversationId) {
        RunControl control = running.get(conversationId);
        if (control == null) {
            return false;
        }
        control.cancel();
        return true;
    }

    /** 插话；返回 false 表示没收下：没有正在运行的任务，或者运行正在收尾（应该作为新消息发送）。 */
    public boolean steer(String conversationId, String text) {
        RunControl control = running.get(conversationId);
        return control != null && control.steer(text);
    }

    /** 手动压缩。压缩期间同样占住会话（要调一次模型，可能很久），可以用 {@link #cancel} 取消。 */
    public Optional<AgentEvent.Compacted> compact(String conversationId) {
        RunControl control = new RunControl();
        claim(conversationId, control);
        try {
            return engine.compact(load(conversationId), null, control);
        } finally {
            release(conversationId, control);
        }
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

    /** 某个用户的会话；owner 为 null 时列出全部。 */
    public List<ConversationSummary> list(int limit, String owner) {
        return repository.list(limit, owner);
    }

    // ------------------------------------------------------------------ 删除

    /** 删除会话（正在运行时拒绝），连同引擎为它保留的工具状态。返回是否真的删了。 */
    public boolean delete(String conversationId) {
        RunControl control = new RunControl();
        claim(conversationId, control);
        try {
            boolean deleted = repository.delete(ConversationId.of(conversationId));
            engine.forgetToolState(conversationId);
            return deleted;
        } finally {
            release(conversationId, control);
        }
    }

    /** 删除 cutoff 之前就不再更新的会话（保留期限），正在运行的除外。返回删除了几个。 */
    public int deleteNotUpdatedSince(Instant cutoff) {
        Set<ConversationId> runningNow = running.keySet().stream().map(ConversationId::of).collect(Collectors.toSet());
        return repository.deleteNotUpdatedSince(cutoff, runningNow);
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
