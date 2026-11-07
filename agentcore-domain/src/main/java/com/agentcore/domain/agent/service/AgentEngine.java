package com.agentcore.domain.agent.service;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.event.AgentEvent.Notice;
import com.agentcore.domain.agent.event.AgentEventListener;
import com.agentcore.domain.agent.event.RunObserver;
import com.agentcore.domain.agent.hook.AgentHook;
import com.agentcore.domain.agent.interceptor.ArgumentsInterceptor;
import com.agentcore.domain.agent.interceptor.HookInterceptor;
import com.agentcore.domain.agent.interceptor.PermissionInterceptor;
import com.agentcore.domain.agent.interceptor.ToolInterceptor;
import com.agentcore.domain.agent.interceptor.TruncateInterceptor;
import com.agentcore.domain.agent.model.AgentDefinition;
import com.agentcore.domain.agent.model.EngineSettings;
import com.agentcore.domain.agent.model.RunControl;
import com.agentcore.domain.agent.model.RunModel;
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
import com.agentcore.domain.shared.util.BoundedLruCache;
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
 * 读代码的顺序：{@code Run#execute}（总流程）→ {@code Run#loop}（一轮一轮跑）→ {@code Run#decideNext}（这一轮之后干什么）。
 * 方法上的 ①～⑩ 与 docs/02-agent-loop.md 的流程图对应。
 *
 * <p>分工：
 * <ul>
 *   <li>本类：主循环和上下文压缩；</li>
 *   <li>{@link ToolExecutor}：执行工具（只读的并行，每个调用过一遍拦截器链，关卡 1～9 见 docs/04）；</li>
 *   <li>{@link RetryingModelGateway}：调模型时的退避重试（装饰器，包在模型外面）。</li>
 * </ul>
 * 只依赖领域端口（ModelGateway、Approver、ConversationRepository、Tool），与具体厂商和框架无关。
 * 引擎本身无状态（每次运行的状态在 {@code Run} 里，工具状态按会话隔离），可被多个会话并发使用。
 */
public final class AgentEngine {

    private static final System.Logger LOG = System.getLogger(AgentEngine.class.getName());

    /** 请求一发出去就超窗时，压缩后重发的次数上限（整次运行累计）。 */
    private static final int MAX_OVERFLOW_COMPACTIONS = 2;
    /** 最多为多少个会话保留工具状态；更早的会话被丢掉（例如文件读取记录没了，写文件前要重新读一次）。 */
    private static final int MAX_CONVERSATIONS_WITH_TOOL_STATE = 1000;

    private static final String CUT_OFF_TOOL_CALL =
            "Error: your response was cut off before this tool call was complete, so it was not executed. "
                    + "Retry with smaller steps (for example, write a large file in several edits).";

    // ---- 构建时传入的配置
    private final AgentDefinition definition;
    private final ModelGateway model;
    private final ModelGateway summarizer;
    private final ToolRegistry tools;
    private final PermissionPolicy permissions;
    private final Approver approver;
    private final List<AgentHook> hooks;
    private final List<ToolInterceptor> interceptors;
    private final List<RunObserver> observers;
    private final ConversationRepository repository;
    private final ConversationCompactor compactor;
    private final Map<Class<?>, Object> services;
    private final int depth;

    // ---- 由配置派生
    /** 带退避重试的模型（装饰器）。model() 仍然返回原始模型。 */
    private final ModelGateway retryingModel;
    /** 带退避重试的摘要模型（和主模型相同时就是 retryingModel）。 */
    private final ModelGateway retryingSummarizer;
    private final ToolExecutor toolExecutor;
    /** 会话级工具状态（文件读取记录、todo 列表等），按会话 ID 隔离；同一会话的多次运行共用。有数量上限。 */
    private final BoundedLruCache<String, Map<String, Object>> toolState = new BoundedLruCache<>(MAX_CONVERSATIONS_WITH_TOOL_STATE);

    private AgentEngine(Builder builder) {
        this.definition = builder.definition;
        this.model = builder.model;
        this.summarizer = builder.summarizer != null ? builder.summarizer : builder.model;
        this.tools = builder.tools;
        this.permissions = builder.permissions;
        this.approver = builder.approver;
        this.hooks = List.copyOf(builder.hooks);
        this.interceptors = List.copyOf(builder.interceptors);
        this.observers = List.copyOf(builder.observers);
        this.repository = builder.repository;
        this.compactor = builder.compactor;
        this.services = Map.copyOf(builder.services);
        this.depth = builder.depth;

        EngineSettings settings = definition.settings();
        this.retryingModel = withRetries(model, settings);
        this.retryingSummarizer = summarizer == model ? retryingModel : withRetries(summarizer, settings);
        this.toolExecutor = new ToolExecutor(tools, interceptorChain(), settings, services, depth);
    }

    private static ModelGateway withRetries(ModelGateway gateway, EngineSettings settings) {
        return new RetryingModelGateway(gateway, settings.maxRetries(), settings.retryBaseDelay(), settings.maxMalformedRetries());
    }

    /** 工具调用的拦截器链：参数校验 → 权限与审批 → 使用者的拦截器 → 钩子 → 截断输出 →（末端）执行。 */
    private List<ToolInterceptor> interceptorChain() {
        List<ToolInterceptor> chain = new ArrayList<>();
        chain.add(new ArgumentsInterceptor());
        chain.add(new PermissionInterceptor(permissions, approver));
        chain.addAll(interceptors);
        chain.add(new HookInterceptor(hooks));
        chain.add(new TruncateInterceptor());
        return chain;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.definition = definition;
        builder.model = model;
        builder.summarizer = summarizer == model ? null : summarizer;
        builder.tools = tools;
        builder.permissions = permissions;
        builder.approver = approver;
        builder.hooks = new ArrayList<>(hooks);
        builder.interceptors = new ArrayList<>(interceptors);
        builder.observers = new ArrayList<>(observers);
        builder.repository = repository;
        builder.compactor = compactor;
        builder.services = new LinkedHashMap<>(services);
        builder.depth = depth;
        return builder;
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

    /** 观察所有运行的观察者（指标等）。 */
    public List<RunObserver> observers() {
        return observers;
    }

    public ConversationCompactor compactor() {
        return compactor;
    }

    public int depth() {
        return depth;
    }

    /**
     * 执行一次运行。input 为 null/空表示在现有历史上继续（例如恢复中断的会话）。
     * 该方法阻塞直到运行结束；所有过程通过 listener 以事件形式发出。
     */
    public RunResult run(Conversation conversation, List<? extends ContentBlock> input, AgentEventListener listener,
                         RunControl control) {
        return run(conversation, input, listener, control, model);
    }

    /**
     * 为这一次运行选择模型，不改变引擎默认模型。模型在 Run 内冻结，运行中配置变更不会影响它。
     * 仍使用同一引擎的工具状态、权限、观察者与仓储，切换模型不会丢掉会话的已读文件记录。
     * 网关的生命周期由调用方管理；引擎不会关闭传入的网关。
     */
    public RunResult run(Conversation conversation, List<? extends ContentBlock> input, AgentEventListener listener,
                         RunControl control, ModelGateway selectedModel) {
        if (selectedModel == null) {
            throw new IllegalArgumentException("selected model is required");
        }
        return new Run(conversation, listener == null ? AgentEventListener.NOOP : listener,
                control == null ? new RunControl() : control, selectedModel).execute(input);
    }

    /** 手动压缩一个会话的上下文。 */
    public Optional<AgentEvent.Compacted> compact(Conversation conversation, AgentEventListener listener) {
        return compact(conversation, listener, new RunControl());
    }

    /** 手动压缩，可以通过 control 取消（生成摘要可能要很久）。 */
    public Optional<AgentEvent.Compacted> compact(Conversation conversation, AgentEventListener listener, RunControl control) {
        List<AgentEvent.Compacted> compacted = new ArrayList<>();
        Run run = new Run(conversation, event -> {
            if (event instanceof AgentEvent.Compacted c) {
                compacted.add(c);
            }
            if (listener != null) {
                listener.onEvent(event);
            }
        }, control == null ? new RunControl() : control);
        run.compact();
        return compacted.stream().findFirst();
    }

    /** 会话被删除时调用：丢掉引擎为它保留的工具状态（文件读取记录、todo 列表）。 */
    public void forgetToolState(String conversationId) {
        toolState.remove(conversationId);
    }

    /** 当前上下文规模估算：有上一次请求的真实用量时以它为基线，只估算之后新增的消息。 */
    public long estimateContextTokens(Conversation conversation) {
        List<Message> messages = conversation.messages();
        Conversation.ContextSnapshot snapshot = conversation.contextSnapshot();
        if (snapshot != null && snapshot.messageCount() <= messages.size()) {
            List<Message> newer = messages.subList(snapshot.messageCount(), messages.size());
            return snapshot.tokens() + TokenEstimator.estimate(newer);
        }
        long fixed = TokenEstimator.estimate(definition.systemPrompt()) + TokenEstimator.estimate(String.valueOf(tools.specs()));
        return fixed + TokenEstimator.estimate(messages);
    }

    // ======================================================================== 单次运行

    /** 运行怎么结束的：停止原因、最终文本、错误信息（只有 ERROR 时有）。 */
    private record Ending(StopReason reason, String text, String error) {
        static Ending of(StopReason reason, String text) {
            return new Ending(reason, text, null);
        }
    }

    /** {@code Run#decideNext} 的返回值之一：不结束，进入下一轮。 */
    private static final Optional<Ending> NEXT_TURN = Optional.empty();

    /**
     * 一次运行的全部状态（会话、监听器、取消/插话控制、轮数、累计用量）。
     * 每次 {@link #run} 新建一个，用完即弃 —— 所以引擎本身可以被多个会话同时使用。
     */
    private final class Run implements ToolExecutor.Scope {
        private final Conversation conversation;
        private final AgentEventListener listener;
        /** 观察者为这次运行给出的监听器（指标等）。 */
        private final List<AgentEventListener> observerListeners;
        private final RunControl control;
        private final ModelGateway runModel;
        private final ModelGateway runRetryingModel;
        private final ModelGateway runRetryingSummarizer;
        private final EngineSettings settings = definition.settings();
        private final Object emitLock = new Object();

        private int turn;
        private volatile TokenUsage usage = TokenUsage.ZERO;
        /** 已经因为"请求超窗"压缩重发过几次。 */
        private int overflowCompactions;
        /** 上一轮生成时撑满了上下文窗口：下一轮开始前必须压缩。 */
        private boolean mustCompact;
        /** 最近一次"模型说完了"的回复。运行后来被取消、出错或到达轮数上限时，作为最终文本返回。 */
        private String lastAnswer = "";

        Run(Conversation conversation, AgentEventListener listener, RunControl control) {
            this(conversation, listener, control, model);
        }

        Run(Conversation conversation, AgentEventListener listener, RunControl control, ModelGateway selectedModel) {
            this.conversation = conversation;
            this.listener = listener;
            this.control = control;
            this.runModel = selectedModel;
            this.runRetryingModel = selectedModel == model ? retryingModel : withRetries(selectedModel, settings);
            // 未单独指定摘要模型时，自动压缩也跟随本次选择；显式摘要模型保持原配置。
            this.runRetryingSummarizer = summarizer == model ? runRetryingModel : retryingSummarizer;
            this.observerListeners = startObservers(conversation.id().value());
        }

        /** 每个观察者为这次运行给一个监听器；某个观察者出错就跳过它。 */
        private List<AgentEventListener> startObservers(String conversationId) {
            List<AgentEventListener> started = new ArrayList<>();
            for (RunObserver observer : observers) {
                try {
                    started.add(observer.startRun(conversationId));
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "run observer failed to start", e);
                }
            }
            return started;
        }

        /** 总流程：准备 → 一轮一轮跑到结束 → 发 RunFinished。取消和出错都不往外抛，同样正常收尾。 */
        RunResult execute(List<? extends ContentBlock> input) {
            Ending ending;
            try {
                start(input);
                ending = loop();
            } catch (CancelledException e) {
                repairQuietly("Cancelled: the run was interrupted before this tool finished.");
                emit(Notice.info("run cancelled"));
                ending = Ending.of(StopReason.CANCELLED, lastAnswer);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "agent run failed", e);
                String error = e.getClass().getSimpleName() + ": " + e.getMessage();
                repairQuietly("Error: the run failed before this tool finished.");
                emit(Notice.error(error));
                ending = new Ending(StopReason.ERROR, lastAnswer, error);
            }
            List<String> unprocessedSteering = closeSteering();
            emit(new AgentEvent.RunFinished(ending.reason(), ending.text(), turn, usage, ending.error()));
            return new RunResult(conversation.id().value(), ending.text(), ending.reason(), turn, usage, ending.error(),
                    unprocessedSteering);
        }

        /** 关闭插话入口；运行没正常结束、还有没处理的插话时，发一条警告（这些文字在 RunResult 里交还给调用方）。 */
        private List<String> closeSteering() {
            List<String> unprocessed = control.close();
            if (!unprocessed.isEmpty()) {
                emit(Notice.warning(unprocessed.size() + " steering message(s) arrived too late and were not processed"));
            }
            return unprocessed;
        }

        /** ①② 准备：清理上次运行的残局，追加用户输入。 */
        private void start(List<? extends ContentBlock> input) {
            // ① 上次运行（崩溃 / 取消）可能留下没有结果的工具调用，补上错误结果，历史才合法
            if (conversation.repairDanglingToolCalls("Interrupted: the previous run ended before this tool finished.")) {
                save();
            }
            // ② input 为空表示在现有历史上继续（例如恢复会话后直接继续跑）
            if (input != null && !input.isEmpty()) {
                conversation.addUserInput(input);
                save();
            }
            emit(new AgentEvent.RunStarted(conversation.id().value(), definition.name(), runModel.describe()));
        }

        /** ③～⑩ 主循环。一轮 = 调一次模型；每轮结束由 decideNext 决定是结束运行还是再来一轮。 */
        private Ending loop() {
            while (true) {
                // ③ 检查取消和轮数上限
                control.token().throwIfCancelled();
                if (turn >= settings.maxTurns()) {
                    emit(Notice.warning("reached max_turns=" + settings.maxTurns()));
                    return Ending.of(StopReason.MAX_TURNS, lastAnswer);
                }
                turn++;
                emit(new AgentEvent.TurnStarted(turn));

                // ④ 上下文快满了（或上一轮生成时撑满了窗口）：先压缩
                if (mustCompact || compactor.shouldCompact(estimateContextTokens(conversation), runModel.contextWindow())) {
                    mustCompact = false;
                    compact();
                }

                ModelResponse response = callModel();          // ⑤⑥
                saveReply(response);                            // ⑦
                Optional<Ending> ending = decideNext(response);  // ⑧⑨⑩
                if (ending.isPresent()) {
                    return ending.get();
                }
            }
        }

        /**
         * ⑤⑥ 组装请求并调模型（流式，增量实时转成事件）。
         * 可重试的错误由 RetryingModelGateway 退避重试；请求本身超窗时先压缩再重发本轮。
         */
        private ModelResponse callModel() {
            while (true) {
                // ⑤ 固定的系统提示词 + 全部历史 + 工具列表；钩子可以改写
                ModelRequest request = ModelRequest.of(definition.systemPrompt(), conversation.messages(), tools.specs());
                for (AgentHook hook : hooks) {
                    request = hook.beforeModel(request);
                }
                // ⑥
                try {
                    return runRetryingModel.stream(request, StreamEventForwarder.forReply(this::emit), control.token());
                } catch (ContextOverflowException e) {
                    if (overflowCompactions++ >= MAX_OVERFLOW_COMPACTIONS) {
                        throw e;
                    }
                    emit(Notice.warning("context overflow, compacting and retrying: " + e.getMessage()));
                    compact();
                }
            }
        }

        /** ⑦ 先保存助手回复，再决定下一步：即使后面执行工具时崩溃，这条回复也已经落盘。 */
        private void saveReply(ModelResponse response) {
            for (AgentHook hook : hooks) {
                hook.afterModel(response);
            }
            conversation.addAssistantReply(response.message(), response.usage());
            save();
            addUsage(response.usage());
            emit(new AgentEvent.AssistantMessageCompleted(response.message(), response.usage(), response.stopReason()));
        }

        /** ⑧ 看这一轮的停止原因，决定下一步：返回 Ending 表示运行结束，返回 NEXT_TURN 表示再来一轮。 */
        private Optional<Ending> decideNext(ModelResponse response) {
            Message reply = response.message();
            List<ToolCall> calls = reply.toolCalls();
            StopReason reason = response.stopReason();
            boolean cutOff = reason == StopReason.MAX_TOKENS || reason == StopReason.CONTEXT_OVERFLOW;

            if (reason == StopReason.PAUSE_TURN && calls.isEmpty()) {
                return NEXT_TURN; // 服务端工具暂停了：原样续发
            }
            if (reason == StopReason.REFUSAL) {
                emit(Notice.warning("model refused the request"
                        + (response.stopDetails() != null ? " (" + response.stopDetails() + ")" : "")));
                if (!calls.isEmpty()) {
                    conversation.repairDanglingToolCalls("Not executed: the response was refused.");
                    save();
                }
                return Optional.of(Ending.of(StopReason.REFUSAL, reply.text()));
            }
            if (cutOff && !calls.isEmpty()) {
                rejectCutOffCalls(calls);
                mustCompact = reason == StopReason.CONTEXT_OVERFLOW;
                return NEXT_TURN;
            }
            if (!calls.isEmpty()) {
                runTools(calls);
                return NEXT_TURN;
            }
            if (cutOff) {
                emit(Notice.warning("the response hit the output token limit and may be incomplete"));
                return Optional.of(Ending.of(StopReason.MAX_TOKENS, reply.text()));
            }
            // ⑩ 模型说完了。用户在运行中插过话：当作新的用户输入，再来一轮；否则关闭插话入口，正常结束
            lastAnswer = reply.text();
            List<ContentBlock> steering = asTextBlocks(control.takeSteeringOrClose());
            if (!steering.isEmpty()) {
                conversation.addUserInput(steering);
                save();
                return NEXT_TURN;
            }
            return Optional.of(Ending.of(StopReason.END_TURN, reply.text()));
        }

        /** ⑨ 执行工具（ToolExecutor：分批并行 + 拦截器链）。结果和运行中用户的插话放进同一条用户消息。 */
        private void runTools(List<ToolCall> calls) {
            List<ToolResult> results = toolExecutor.execute(calls, this);
            conversation.answerToolCalls(results, takeSteering());
            save();
        }

        /** 回复被截断时，工具参数可能不完整：绝不执行，回填错误让模型分步重试。 */
        private void rejectCutOffCalls(List<ToolCall> calls) {
            List<ToolResult> errors = calls.stream()
                    .map(call -> ToolResult.text(call.id(), call.name(), CUT_OFF_TOOL_CALL, true))
                    .toList();
            conversation.answerToolCalls(errors, List.of());
            save();
        }

        /** 取走运行中用户的插话，转成文本块。 */
        private List<ContentBlock> takeSteering() {
            return asTextBlocks(control.drainSteering());
        }

        private static List<ContentBlock> asTextBlocks(List<String> texts) {
            return texts.stream().<ContentBlock>map(TextBlock::new).toList();
        }

        /** 运行异常结束时补齐工具结果；补的过程本身出错也只记日志，不掩盖原来的异常。 */
        private void repairQuietly(String reason) {
            try {
                if (conversation.repairDanglingToolCalls(reason)) {
                    save();
                }
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "failed to repair history", ex);
            }
        }

        /**
         * 压缩上下文：旧对话 → 摘要 → 用"摘要 + 最新用户输入"替换历史。
         * 摘要用带重试的模型生成，可以被取消。失败只发警告、不中断运行（下一轮还会再试）；取消照常向上抛。
         */
        void compact() {
            // 决定保留什么：最后一条是普通用户输入就保留它，其余全部总结
            ConversationCompactor.Plan plan = compactor.plan(conversation.messages());
            if (plan.toSummarize().isEmpty()) {
                return;
            }
            long before = estimateContextTokens(conversation);
            try {
                ConversationCompactor.Summary summary = compactor.summarize(plan.toSummarize(), runRetryingSummarizer,
                        StreamEventForwarder.retryNoticesOnly(this::emit), control.token());
                addUsage(summary.usage());
                conversation.recordUsage(summary.usage(), "compaction");
                conversation.compact(summary.text(), compactor.buildCompactedHistory(summary.text(), plan.keep()));
                save();
                emit(new AgentEvent.Compacted(before, estimateContextTokens(conversation), summary.text()));
            } catch (CancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "compaction failed", e);
                emit(Notice.warning("context compaction failed: " + e.getMessage()));
            }
        }

        // ---- ToolExecutor.Scope：工具执行器通过这几个方法访问本次运行

        /** 发事件给调用方的监听器和各个观察者。工具并行执行时会从多个线程调用，加锁保证事件不交错；监听器出错不影响运行。 */
        @Override
        public void emit(AgentEvent event) {
            synchronized (emitLock) {
                deliver(listener, event);
                for (AgentEventListener observer : observerListeners) {
                    deliver(observer, event);
                }
            }
        }

        private static void deliver(AgentEventListener target, AgentEvent event) {
            try {
                target.onEvent(event);
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "event listener failed", ex);
            }
        }

        @Override
        public synchronized void addUsage(TokenUsage more) {
            usage = usage.plus(more);
        }

        @Override
        public Conversation conversation() {
            return conversation;
        }

        @Override
        public CancellationToken cancelToken() {
            return control.token();
        }

        @Override
        public RunModel runModel() {
            return new RunModel(runModel);
        }

        @Override
        public Map<String, Object> toolState() {
            return toolState.computeIfAbsent(conversation.id().value(), id -> new ConcurrentHashMap<>());
        }

        /** 把聚合上新产生的领域事件写入仓储。每一步状态变化后都调用，进程随时崩溃也能从磁盘恢复。 */
        @Override
        public void save() {
            if (repository != null) {
                repository.save(conversation);
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
        private List<RunObserver> observers = new ArrayList<>();
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

        /** 加一个观察所有运行的观察者（指标、审计日志）。 */
        public Builder observer(RunObserver o) {
            this.observers.add(o);
            return this;
        }

        public Builder observers(List<RunObserver> list) {
            this.observers = new ArrayList<>(list);
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
