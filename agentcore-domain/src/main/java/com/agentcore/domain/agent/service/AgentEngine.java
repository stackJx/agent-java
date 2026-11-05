package com.agentcore.domain.agent.service;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.event.AgentEventListener;
import com.agentcore.domain.agent.hook.AgentHook;
import com.agentcore.domain.agent.interceptor.ArgumentsInterceptor;
import com.agentcore.domain.agent.interceptor.HookInterceptor;
import com.agentcore.domain.agent.interceptor.PermissionInterceptor;
import com.agentcore.domain.agent.interceptor.ToolInterceptor;
import com.agentcore.domain.agent.interceptor.TruncateInterceptor;
import com.agentcore.domain.agent.model.AgentDefinition;
import com.agentcore.domain.agent.model.EngineSettings;
import com.agentcore.domain.agent.model.RunControl;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.context.service.ConversationCompactor;
import com.agentcore.domain.context.service.TokenEstimator;
import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.conversation.repository.ConversationRepository;
import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.gateway.RetryingModelGateway;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.permission.gateway.Approver;
import com.agentcore.domain.permission.service.PermissionPolicy;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.cancel.CancelledException;
import com.agentcore.domain.tool.model.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 引擎（领域服务）：驱动一次运行的完整循环。
 *
 * <pre>
 * 用户输入 → [必要时压缩上下文] → 调模型(流式，自动重试) → 有工具调用?
 *    ├─ 是：交给 ToolExecutor（只读工具并行；每个调用走一遍拦截器链）→ 回填 → 下一轮
 *    └─ 否：结束（或处理 max_tokens / refusal / pause_turn / 超窗）
 * </pre>
 *
 * 分工：
 * <ul>
 *   <li>本类：主循环 ①～⑩（{@code Run#execute}）和上下文压缩（{@code Run#compact}）；</li>
 *   <li>{@link ToolExecutor}：工具分批并行执行；单个调用的关卡 1～9 分散在它和 {@code agent.interceptor} 的拦截器里；</li>
 *   <li>{@link RetryingModelGateway}：调模型时的退避重试（装饰器，包在模型外面）。</li>
 * </ul>
 * 只依赖领域端口（ModelGateway、Approver、ConversationRepository、Tool），与具体厂商和框架无关。
 * 引擎本身无状态（每个会话的工具状态按会话隔离），可被多个会话并发使用。
 * 注释里的 ①～⑩ 与 docs/02-agent-loop.md 的流程图对应，1～9 与 docs/04-tools-and-permissions.md 的关卡图对应。
 */
public final class AgentEngine {

    private static final System.Logger LOG = System.getLogger(AgentEngine.class.getName());

    private final AgentDefinition definition;
    private final ModelGateway model;
    private final ModelGateway summarizer;
    private final ToolRegistry tools;
    private final PermissionPolicy permissions;
    private final Approver approver;
    private final List<AgentHook> hooks;
    private final List<ToolInterceptor> interceptors;
    private final ConversationRepository repository;
    private final ConversationCompactor compactor;
    private final Map<Class<?>, Object> services;
    private final int depth;
    /** 会话级工具状态（文件读取记录、todo 列表等），按会话 ID 隔离；同一会话的多次运行共用。 */
    private final Map<String, Map<String, Object>> toolState = new ConcurrentHashMap<>();
    /** 带退避重试的模型（装饰器）。model() 仍然返回原始模型。 */
    private final ModelGateway retryingModel;
    private final ToolExecutor toolExecutor;

    private AgentEngine(Builder b) {
        this.definition = b.definition;
        this.model = b.model;
        this.summarizer = b.summarizer != null ? b.summarizer : b.model;
        this.tools = b.tools;
        this.permissions = b.permissions;
        this.approver = b.approver;
        this.hooks = List.copyOf(b.hooks);
        this.interceptors = List.copyOf(b.interceptors);
        this.repository = b.repository;
        this.compactor = b.compactor;
        this.services = Map.copyOf(b.services);
        this.depth = b.depth;
        EngineSettings s = definition.settings();
        this.retryingModel = new RetryingModelGateway(model, s.maxRetries(), s.retryBaseDelay(), s.maxMalformedRetries());
        // 工具调用的拦截器链：参数校验 → 权限与审批 → 使用者的拦截器 → 钩子 → 截断输出 →（末端）执行
        List<ToolInterceptor> chain = new ArrayList<>();
        chain.add(new ArgumentsInterceptor());
        chain.add(new PermissionInterceptor(permissions, approver));
        chain.addAll(interceptors);
        chain.add(new HookInterceptor(hooks));
        chain.add(new TruncateInterceptor());
        this.toolExecutor = new ToolExecutor(tools, chain, s, services, depth);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.definition = definition;
        b.model = model;
        b.summarizer = summarizer == model ? null : summarizer;
        b.tools = tools;
        b.permissions = permissions;
        b.approver = approver;
        b.hooks = new ArrayList<>(hooks);
        b.interceptors = new ArrayList<>(interceptors);
        b.repository = repository;
        b.compactor = compactor;
        b.services = new LinkedHashMap<>(services);
        b.depth = depth;
        return b;
    }

    public AgentDefinition definition() {
        return definition;
    }

    public ModelGateway model() {
        return model;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public PermissionPolicy permissions() {
        return permissions;
    }

    public Approver approver() {
        return approver;
    }

    public List<AgentHook> hooks() {
        return hooks;
    }

    /** 使用者加入的工具调用拦截器（不含内置的参数校验、权限、钩子、截断）。 */
    public List<ToolInterceptor> interceptors() {
        return interceptors;
    }

    public ConversationCompactor compactor() {
        return compactor;
    }

    public int depth() {
        return depth;
    }

    /** 当前上下文规模估算：有上一次请求的真实用量时以它为基线，只估算之后新增的消息。 */
    public long estimateContextTokens(Conversation conversation) {
        List<Message> msgs = conversation.messages();
        Conversation.ContextSnapshot snap = conversation.contextSnapshot();
        if (snap != null && snap.messageCount() <= msgs.size()) {
            return snap.tokens() + TokenEstimator.estimate(msgs.subList(snap.messageCount(), msgs.size()));
        }
        long fixed = TokenEstimator.estimate(definition.systemPrompt()) + TokenEstimator.estimate(String.valueOf(tools.specs()));
        return fixed + TokenEstimator.estimate(msgs);
    }

    /**
     * 执行一次运行。input 为 null/空表示在现有历史上继续（例如恢复中断的会话）。
     * 该方法阻塞直到运行结束；所有过程通过 listener 以事件形式发出。
     */
    public RunResult run(Conversation conversation, List<? extends ContentBlock> input, AgentEventListener listener,
                         RunControl control) {
        return new Run(conversation, listener == null ? AgentEventListener.NOOP : listener,
                control == null ? new RunControl() : control).execute(input);
    }

    /** 手动压缩一个会话的上下文。 */
    public Optional<AgentEvent.Compacted> compact(Conversation conversation, AgentEventListener listener) {
        List<AgentEvent.Compacted> out = new ArrayList<>();
        Run run = new Run(conversation, e -> {
            if (e instanceof AgentEvent.Compacted c) {
                out.add(c);
            }
            if (listener != null) {
                listener.onEvent(e);
            }
        }, new RunControl());
        run.compact();
        return out.stream().findFirst();
    }

    private Map<String, Object> stateFor(Conversation c) {
        return toolState.computeIfAbsent(c.id().value(), k -> new ConcurrentHashMap<>());
    }

    // ======================================================================== 单次运行

    /**
     * 一次运行的全部状态（会话、监听器、取消/插话控制、累计用量）。
     * 每次 {@link #run} 新建一个，用完即弃 —— 所以引擎本身可以被多个会话同时使用。
     */
    private final class Run implements ToolExecutor.Scope {
        private final Conversation conversation;
        private final AgentEventListener listener;
        private final RunControl control;
        private final Object emitLock = new Object();
        private volatile TokenUsage usage = TokenUsage.ZERO;
        private boolean forceCompact;

        Run(Conversation conversation, AgentEventListener listener, RunControl control) {
            this.conversation = conversation;
            this.listener = listener;
            this.control = control;
        }

        /** 发事件。工具并行执行时会从多个线程调用，加锁保证监听器看到的事件不交错；监听器出错不影响运行。 */
        @Override
        public void emit(AgentEvent e) {
            synchronized (emitLock) {
                try {
                    listener.onEvent(e);
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.WARNING, "event listener failed", ex);
                }
            }
        }

        @Override
        public synchronized void addUsage(TokenUsage u) {
            usage = usage.plus(u);
        }

        @Override
        public Conversation conversation() {
            return conversation;
        }

        @Override
        public CancellationToken cancel() {
            return control.token();
        }

        @Override
        public Map<String, Object> toolState() {
            return stateFor(conversation);
        }

        /** 把聚合上新产生的领域事件写入仓储。每一步状态变化后都调用，进程随时崩溃也能从磁盘恢复。 */
        @Override
        public void save() {
            if (repository != null) {
                repository.save(conversation);
            }
        }

        RunResult execute(List<? extends ContentBlock> input) {
            int turn = 0;
            int overflowRetries = 0;
            String finalText = "";
            StopReason stop = StopReason.END_TURN;
            String error = null;
            EngineSettings s = definition.settings();
            try {
                // ① 修复残局：上次运行（崩溃 / 取消）可能留下没有结果的工具调用，补上错误结果，历史才合法
                if (conversation.repairDanglingToolCalls("Interrupted: the previous run ended before this tool finished.")) {
                    save();
                }
                // ② 追加用户输入。input 为空表示在现有历史上继续（例如恢复会话后直接继续跑）
                if (input != null && !input.isEmpty()) {
                    conversation.addUserInput(input);
                    save();
                }
                emit(new AgentEvent.RunStarted(conversation.id().value(), definition.name(), model.describe()));
                while (true) {
                    // ③ 每轮开始：检查取消和轮数上限
                    control.token().throwIfCancelled();
                    if (turn >= s.maxTurns()) {
                        stop = StopReason.MAX_TURNS;
                        emit(new AgentEvent.Notice("warning", "reached max_turns=" + s.maxTurns()));
                        break;
                    }
                    turn++;
                    emit(new AgentEvent.TurnStarted(turn));

                    // ④ 上下文快满了（或上一轮生成时撑满了窗口）：先压缩
                    if (forceCompact || compactor.shouldCompact(estimateContextTokens(conversation), model.contextWindow())) {
                        forceCompact = false;
                        compact();
                    }

                    // ⑤ 组装请求：固定的系统提示词 + 全部历史 + 工具列表；钩子可以改写
                    ModelRequest request = ModelRequest.of(definition.systemPrompt(), conversation.messages(), tools.specs());
                    for (AgentHook h : hooks) {
                        request = h.beforeModel(request);
                    }
                    // ⑥ 调模型（流式，增量实时转成事件）。可重试的错误由 RetryingModelGateway 退避重试；
                    //    请求本身超窗时压缩后重做本轮（turn-- 让这一轮不计数），最多 2 次
                    ModelResponse response;
                    try {
                        response = retryingModel.stream(request, new StreamEventForwarder(this::emit), control.token());
                    } catch (ContextOverflowException e) {
                        if (overflowRetries++ >= 2) {
                            throw e;
                        }
                        emit(new AgentEvent.Notice("warning", "context overflow, compacting and retrying: " + e.getMessage()));
                        compact();
                        turn--;
                        continue;
                    }
                    for (AgentHook h : hooks) {
                        h.afterModel(response);
                    }

                    // ⑦ 先保存助手回复，再决定下一步。即使后面执行工具时崩溃，这条回复也已经落盘
                    Message msg = response.message();
                    conversation.addAssistantReply(msg, response.usage());
                    save();
                    addUsage(response.usage());
                    emit(new AgentEvent.AssistantMessageCompleted(msg, response.usage(), response.stopReason()));

                    // ⑧ 按停止原因分支
                    StopReason sr = response.stopReason();
                    List<ToolCall> calls = msg.toolCalls();
                    boolean truncated = sr == StopReason.MAX_TOKENS || sr == StopReason.CONTEXT_OVERFLOW;
                    if (sr == StopReason.PAUSE_TURN && calls.isEmpty()) {
                        continue; // 服务端工具暂停：原样续发
                    }
                    if (sr == StopReason.REFUSAL) {
                        stop = StopReason.REFUSAL;
                        finalText = msg.text();
                        emit(new AgentEvent.Notice("warning", "model refused the request"
                                + (response.stopDetails() != null ? " (" + response.stopDetails() + ")" : "")));
                        if (!calls.isEmpty()) {
                            conversation.repairDanglingToolCalls("Not executed: the response was refused.");
                            save();
                        }
                        break;
                    }
                    if (truncated && !calls.isEmpty()) {
                        // 工具参数可能被截断：绝不执行，回填错误让模型分步重试
                        List<ToolResult> errs = calls.stream().map(c -> ToolResult.text(c.id(), c.name(),
                                "Error: your response was cut off before this tool call was complete, so it was not executed. "
                                        + "Retry with smaller steps (for example, write a large file in several edits).", true)).toList();
                        conversation.answerToolCalls(errs, List.of());
                        save();
                        forceCompact = sr == StopReason.CONTEXT_OVERFLOW;
                        continue;
                    }
                    if (!calls.isEmpty()) {
                        // ⑨ 执行工具（ToolExecutor：分批并行 + 拦截器链），结果和运行中用户的插话放进同一条用户消息，然后进入下一轮
                        List<ToolResult> results = toolExecutor.execute(calls, this);
                        List<ContentBlock> extra = control.drainSteering().stream().<ContentBlock>map(TextBlock::new).toList();
                        conversation.answerToolCalls(results, extra);
                        save();
                        continue;
                    }
                    if (truncated) {
                        stop = StopReason.MAX_TOKENS;
                        finalText = msg.text();
                        emit(new AgentEvent.Notice("warning", "the response hit the output token limit and may be incomplete"));
                        break;
                    }
                    // ⑩ 模型说完了。如果用户在运行中插过话，把它当作新的输入继续跑；否则正常结束
                    finalText = msg.text();
                    List<String> pending = control.drainSteering();
                    if (!pending.isEmpty()) {
                        conversation.addUserInput(pending.stream().<ContentBlock>map(TextBlock::new).toList());
                        save();
                        continue;
                    }
                    stop = StopReason.END_TURN;
                    break;
                }
            } catch (CancelledException e) {
                // 取消和出错都不往外抛：补齐工具结果保证历史合法，然后以 RunFinished 正常收尾
                stop = StopReason.CANCELLED;
                repairQuietly("Cancelled: the run was interrupted before this tool finished.");
                emit(new AgentEvent.Notice("info", "run cancelled"));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "agent run failed", e);
                stop = StopReason.ERROR;
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                repairQuietly("Error: the run failed before this tool finished.");
                emit(new AgentEvent.Notice("error", error));
            }
            emit(new AgentEvent.RunFinished(stop, finalText, turn, usage, error));
            return new RunResult(conversation.id().value(), finalText, stop, turn, usage, error);
        }

        private void repairQuietly(String reason) {
            try {
                if (conversation.repairDanglingToolCalls(reason)) {
                    save();
                }
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "failed to repair history", ex);
            }
        }

        // ------------------------------------------------------------ 压缩

        /**
         * 压缩上下文：旧对话 → 摘要 → 用"摘要 + 最新用户输入"替换历史。
         * 失败只发警告、不中断运行（下一轮还会再试）；取消照常向上抛。
         */
        void compact() {
            // 决定保留什么：最后一条是普通用户输入就保留它，其余全部总结
            ConversationCompactor.Plan plan = compactor.plan(conversation.messages());
            if (plan.toSummarize().isEmpty()) {
                return;
            }
            long before = estimateContextTokens(conversation);
            try {
                ConversationCompactor.Summary summary = compactor.summarize(plan.toSummarize(), summarizer);
                addUsage(summary.usage());
                conversation.recordUsage(summary.usage(), "compaction");
                conversation.compact(summary.text(), compactor.buildCompactedHistory(summary.text(), plan.keep()));
                save();
                emit(new AgentEvent.Compacted(before, estimateContextTokens(conversation), summary.text()));
            } catch (CancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "compaction failed", e);
                emit(new AgentEvent.Notice("warning", "context compaction failed: " + e.getMessage()));
            }
        }
    }

    // ======================================================================== Builder

    public static final class Builder {
        private AgentDefinition definition;
        private ModelGateway model;
        private ModelGateway summarizer;
        private ToolRegistry tools = new ToolRegistry();
        private PermissionPolicy permissions = PermissionPolicy.defaults();
        private Approver approver;
        private List<AgentHook> hooks = new ArrayList<>();
        private List<ToolInterceptor> interceptors = new ArrayList<>();
        private ConversationRepository repository;
        private ConversationCompactor compactor = new ConversationCompactor(null);
        private Map<Class<?>, Object> services = new LinkedHashMap<>();
        private int depth;

        public Builder definition(AgentDefinition d) {
            this.definition = d;
            return this;
        }

        public Builder model(ModelGateway m) {
            this.model = m;
            return this;
        }

        /** 用于上下文压缩的模型（可选，可用更便宜的模型）。 */
        public Builder summarizer(ModelGateway m) {
            this.summarizer = m;
            return this;
        }

        public Builder tools(ToolRegistry t) {
            this.tools = t;
            return this;
        }

        public Builder permissions(PermissionPolicy p) {
            this.permissions = p;
            return this;
        }

        public Builder approver(Approver a) {
            this.approver = a;
            return this;
        }

        public Builder hook(AgentHook h) {
            this.hooks.add(h);
            return this;
        }

        public Builder hooks(List<AgentHook> h) {
            this.hooks = new ArrayList<>(h);
            return this;
        }

        /** 加一个工具调用拦截器：排在参数校验、权限审批之后，钩子之前（见 {@link ToolInterceptor}）。 */
        public Builder interceptor(ToolInterceptor i) {
            this.interceptors.add(i);
            return this;
        }

        public Builder interceptors(List<ToolInterceptor> list) {
            this.interceptors = new ArrayList<>(list);
            return this;
        }

        public Builder repository(ConversationRepository r) {
            this.repository = r;
            return this;
        }

        public Builder compactor(ConversationCompactor c) {
            this.compactor = c;
            return this;
        }

        /** 注入给工具使用的服务（ToolContext#service 按类型查找）。 */
        public <T> Builder service(Class<T> type, T service) {
            this.services.put(type, service);
            return this;
        }

        public Builder depth(int d) {
            this.depth = d;
            return this;
        }

        public AgentEngine build() {
            if (definition == null || model == null) {
                throw new IllegalStateException("definition and model are required");
            }
            return new AgentEngine(this);
        }
    }
}
