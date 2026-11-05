package com.agentcore.domain.agent.service;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.event.AgentEventListener;
import com.agentcore.domain.agent.hook.AgentHook;
import com.agentcore.domain.agent.hook.HookDecision;
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
import com.agentcore.domain.llm.exception.MalformedOutputException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.permission.gateway.Approver;
import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.permission.model.PermissionRequest;
import com.agentcore.domain.permission.service.PermissionPolicy;
import com.agentcore.domain.shared.cancel.CancelledException;
import com.agentcore.domain.shared.util.Texts;
import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.model.ToolRegistry;
import com.agentcore.domain.tool.schema.SchemaValidator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Agent 引擎（领域服务）：驱动一次运行的完整循环。
 *
 * <pre>
 * 用户输入 → [必要时压缩上下文] → 调模型(流式) → 有工具调用?
 *    ├─ 是：参数校验 → 权限策略/审批 → beforeTool 钩子 → 执行（只读工具并行）→ afterTool 钩子 → 回填 → 下一轮
 *    └─ 否：结束（或处理 max_tokens / refusal / pause_turn / 超窗）
 * </pre>
 *
 * 只依赖领域端口（ModelGateway、Approver、ConversationRepository、Tool），与具体厂商和框架无关。
 * 引擎本身无状态（每个会话的工具状态按会话隔离），可被多个会话并发使用。
 *
 * <p>阅读顺序：{@code Run#execute}（主循环）→ {@code Run#callModel}（重试）→ {@code Run#executeTools}（分批并行）
 * → {@code Run#runTool}（单个工具调用的各道关卡）→ {@code Run#compact}（压缩）。
 * 注释里的 ①～⑩ 与 docs/02-agent-loop.md 的流程图对应，1～9 与 docs/04-tools-and-permissions.md 的关卡图对应。
 */
public final class AgentEngine {

    private static final System.Logger LOG = System.getLogger(AgentEngine.class.getName());
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final AgentDefinition definition;
    private final ModelGateway model;
    private final ModelGateway summarizer;
    private final ToolRegistry tools;
    private final PermissionPolicy permissions;
    private final Approver approver;
    private final List<AgentHook> hooks;
    private final ConversationRepository repository;
    private final ConversationCompactor compactor;
    private final Map<Class<?>, Object> services;
    private final int depth;
    /** 会话级工具状态（文件读取记录、todo 列表等），按会话 ID 隔离；同一会话的多次运行共用。 */
    private final Map<String, Map<String, Object>> toolState = new ConcurrentHashMap<>();
    /** 审批串行化：多个工具同时需要审批时一个一个问，避免提示交错。 */
    private final ReentrantLock approvalLock = new ReentrantLock();

    private AgentEngine(Builder b) {
        this.definition = b.definition;
        this.model = b.model;
        this.summarizer = b.summarizer != null ? b.summarizer : b.model;
        this.tools = b.tools;
        this.permissions = b.permissions;
        this.approver = b.approver;
        this.hooks = List.copyOf(b.hooks);
        this.repository = b.repository;
        this.compactor = b.compactor;
        this.services = Map.copyOf(b.services);
        this.depth = b.depth;
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
    private final class Run {
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
        void emit(AgentEvent e) {
            synchronized (emitLock) {
                try {
                    listener.onEvent(e);
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.WARNING, "event listener failed", ex);
                }
            }
        }

        synchronized void addUsage(TokenUsage u) {
            usage = usage.plus(u);
        }

        /** 把聚合上新产生的领域事件写入仓储。每一步状态变化后都调用，进程随时崩溃也能从磁盘恢复。 */
        void save() {
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
                    // ⑥ 调模型（流式，增量实时转成事件）。可重试的错误在 callModel 里退避重试；
                    //    请求本身超窗时压缩后重做本轮（turn-- 让这一轮不计数），最多 2 次
                    ModelResponse response;
                    try {
                        response = callModel(request);
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
                        // ⑨ 执行工具，结果和运行中用户的插话放进同一条用户消息，然后进入下一轮
                        List<ToolResult> results = executeTools(calls);
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

        // ------------------------------------------------------------ 模型调用

        /**
         * 调一次模型，尽量让它成功：可重试错误（限流 / 5xx / 网络）按 2s × 2^n + 随机抖动退避重试；
         * 输出无法解析时重发本轮；超窗交给调用方压缩。适配器负责把各家错误翻译成这几类异常。
         */
        private ModelResponse callModel(ModelRequest request) {
            int malformed = 0;
            int attempt = 0;
            EngineSettings s = definition.settings();
            // 适配器的流式回调 → 引擎事件（命令行、SSE 据此实时显示）
            ModelStreamListener sl = new ModelStreamListener() {
                @Override
                public void onText(String delta) {
                    emit(new AgentEvent.TextDelta(delta));
                }

                @Override
                public void onThinking(String delta) {
                    emit(new AgentEvent.ThinkingDelta(delta));
                }

                @Override
                public void onToolCallStart(String id, String name) {
                    emit(new AgentEvent.ToolCallStreaming(id, name));
                }
            };
            while (true) {
                control.token().throwIfCancelled();
                try {
                    return model.stream(request, sl, control.token());
                } catch (CancelledException e) {
                    throw e;
                } catch (MalformedOutputException e) {
                    if (++malformed > s.maxMalformedRetries()) {
                        throw e;
                    }
                    emit(new AgentEvent.Notice("warning", "malformed model output, retrying the turn: " + e.getMessage()));
                } catch (ContextOverflowException e) {
                    throw e;
                } catch (ModelException e) {
                    if (control.isCancelled()) {
                        throw new CancelledException();
                    }
                    if (!e.retryable() || attempt >= s.maxRetries()) {
                        throw e;
                    }
                    long delay = s.retryBaseDelay().toMillis() * (1L << attempt) + ThreadLocalRandom.current().nextLong(0, 1000);
                    attempt++;
                    emit(new AgentEvent.Retrying(attempt, delay, Texts.clip(e.getMessage(), 300)));
                    sleepCancellable(delay);
                }
            }
        }

        private void sleepCancellable(long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                control.token().throwIfCancelled();
                try {
                    Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                }
            }
        }

        // ------------------------------------------------------------ 工具执行

        /**
         * 连续的可并行工具一起并发执行，其余按顺序执行；结果保持调用顺序。
         * 例：[read A, read B, write C, read D] → 批次 [A, B]（并行）、[C]、[D]，
         * 保证 C 写之前 A、B 已读完，D 读到的是 C 写之后的内容。
         */
        private List<ToolResult> executeTools(List<ToolCall> calls) {
            // 切分批次：只有"上一批也是可并行的"才并进去，所以批次只合并相邻的只读调用
            List<List<ToolCall>> batches = new ArrayList<>();
            List<Boolean> parallel = new ArrayList<>();
            for (ToolCall c : calls) {
                boolean safe = tools.find(c.name()).map(Tool::concurrencySafe).orElse(false);
                if (safe && !batches.isEmpty() && parallel.get(parallel.size() - 1)) {
                    batches.get(batches.size() - 1).add(c);
                } else {
                    batches.add(new ArrayList<>(List.of(c)));
                    parallel.add(safe);
                }
            }
            Map<String, ToolResult> results = new ConcurrentHashMap<>();
            // 并行批次里同时运行的工具数上限（maxToolConcurrency，默认 8）
            Semaphore permits = new Semaphore(Math.max(1, definition.settings().maxToolConcurrency()));
            try {
                for (int i = 0; i < batches.size(); i++) {
                    control.token().throwIfCancelled();
                    List<ToolCall> group = batches.get(i);
                    if (parallel.get(i) && group.size() > 1) {
                        List<Future<?>> futures = new ArrayList<>();
                        for (ToolCall c : group) {
                            futures.add(EXECUTOR.submit(() -> {
                                permits.acquireUninterruptibly();
                                try {
                                    results.put(c.id(), runTool(c));
                                } finally {
                                    permits.release();
                                }
                                return null;
                            }));
                        }
                        // 取消时中断所有还在跑的工具线程
                        Runnable unregister = control.token().onCancel(() -> futures.forEach(f -> f.cancel(true)));
                        try {
                            for (Future<?> f : futures) {
                                try {
                                    f.get();
                                } catch (ExecutionException e) {
                                    if (e.getCause() instanceof CancelledException ce) {
                                        throw ce;
                                    }
                                    throw new IllegalStateException(e.getCause());
                                } catch (java.util.concurrent.CancellationException | InterruptedException e) {
                                    throw new CancelledException();
                                }
                            }
                        } finally {
                            unregister.run();
                        }
                    } else {
                        for (ToolCall c : group) {
                            results.put(c.id(), runTool(c));
                        }
                    }
                }
            } catch (CancelledException e) {
                // 取消时也要保证每个调用都有结果，历史才合法
                List<ToolResult> filled = calls.stream().map(c -> results.getOrDefault(c.id(),
                        ToolResult.text(c.id(), c.name(), "Cancelled by user.", true))).toList();
                conversation.answerToolCalls(filled, List.of());
                save();
                throw e;
            }
            return calls.stream().map(c -> results.get(c.id())).toList();
        }

        /**
         * 执行单个工具调用。任何一关没通过都不抛异常，而是返回一个错误结果交给模型，让它自己调整做法。
         * 关卡顺序：1 工具存在 → 2 参数是 JSON → 3 符合 Schema → 4 权限策略 → 5 审批 → 6 beforeTool 钩子
         * → 7 执行（超时 / 取消）→ 8 截断过长输出 → 9 afterTool 钩子。
         */
        private ToolResult runTool(ToolCall call) {
            long t0 = System.nanoTime();
            // 1. 工具存在
            Optional<Tool> found = tools.find(call.name());
            if (found.isEmpty()) {
                return fail(call, t0, "Error: unknown tool '" + call.name() + "'. Available tools: " + String.join(", ", tools.names()));
            }
            Tool tool = found.get();
            // 2. 参数是合法 JSON（适配器解析时已经记下 parseError）
            if (call.parseError() != null) {
                return fail(call, t0, "Error: invalid tool arguments: " + call.parseError()
                        + ". Send a valid JSON object matching the schema.");
            }
            // 3. 符合工具的 JSON Schema；顺带做温和的类型转换（例如 "3" → 3），转换后的值才交给工具
            SchemaValidator.Result vr = SchemaValidator.validate(call.arguments(), tool.inputSchema());
            if (!vr.valid()) {
                return fail(call, t0, "Error: invalid arguments for " + tool.name() + ":\n- "
                        + String.join("\n- ", vr.errors().subList(0, Math.min(10, vr.errors().size()))));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> args = vr.value() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

            // 4. 权限策略（默认实现：deny > ask > allow > 模式默认值；可以整个替换成自己的实现）
            PermissionPolicy.Verdict verdict = permissions.check(tool, args);
            Decision decision = verdict.decision();
            String reason = verdict.reason();
            // 5. 需要询问：交给审批人；没有审批人（无人值守）时一律拒绝
            if (decision == Decision.ASK) {
                if (approver == null) {
                    decision = Decision.DENY;
                    reason = "this action requires user approval, but no approver is available (non-interactive run)";
                } else {
                    PermissionRequest req = new PermissionRequest(UUID.randomUUID().toString(), conversation.id().value(),
                            tool.name(), args, tool.permissionSubject(args), tool.describe(args), reason);
                    Approval approval;
                    approvalLock.lock(); // 审批串行，避免多个提示交错
                    try {
                        control.token().throwIfCancelled();
                        approval = approver.approve(req);
                    } finally {
                        approvalLock.unlock();
                    }
                    // 用户选了"总是允许"：交给策略记住（默认实现会生成一条 allow 规则，本进程内后续同类调用不再询问）
                    if (approval.decision() == Decision.ALLOW && approval.remember()) {
                        String remembered = permissions.remember(tool, args);
                        reason = remembered != null ? "always allowed (" + remembered + ")" : "approved by user";
                    } else if (approval.message() != null && !approval.message().isBlank()) {
                        reason = approval.message();
                    } else {
                        reason = approval.decision() == Decision.ALLOW ? "approved by user" : "denied by user";
                    }
                    decision = approval.decision() == Decision.ALLOW ? Decision.ALLOW : Decision.DENY;
                }
                emit(new AgentEvent.PermissionDecided(call.id(), tool.name(), decision.name().toLowerCase(), reason));
            }
            if (decision == Decision.DENY) {
                return fail(call, t0, "Permission denied: " + reason
                        + ". Do not retry the same action; adjust your approach or ask the user.");
            }

            ToolContext ctx = new ToolContext(definition.settings().cwd(), conversation.id().value(), call.id(),
                    stateFor(conversation), msg -> emit(new AgentEvent.ToolProgress(call.id(), msg)), control.token(),
                    services, depth);
            // 6. beforeTool 钩子：可以拦截（原因交给模型）或改写参数
            for (AgentHook h : hooks) {
                HookDecision hd = h.beforeTool(call, tool, args, ctx);
                if (hd != null && hd.blocked()) {
                    return fail(call, t0, "Blocked by policy hook: " + hd.message());
                }
                if (hd != null && hd.rewrittenArguments() != null) {
                    args = hd.rewrittenArguments();
                }
            }

            // 7. 执行。ToolException 是"模型可以自己修正的错误"；其他异常、超时也都变成错误结果；取消则向上抛
            emit(new AgentEvent.ToolStarted(call, tool.describe(args)));
            ToolOutput out;
            try {
                out = invoke(tool, args, ctx);
                if (out == null) {
                    out = ToolOutput.text("(no output)");
                }
            } catch (ToolException e) {
                out = ToolOutput.error("Error: " + e.getMessage());
            } catch (TimeoutException e) {
                out = ToolOutput.error("Error: tool timed out after " + tool.timeout().toSeconds() + "s");
            } catch (CancelledException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            } catch (Exception e) {
                if (control.isCancelled()) {
                    throw new CancelledException();
                }
                LOG.log(System.Logger.Level.WARNING, "tool " + call.name() + " crashed", e);
                out = ToolOutput.error("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            // 8. 输出太长就截掉中间部分，防止一个工具结果撑爆上下文
            out = truncate(out, tool.maxOutputChars());
            // 9. afterTool 钩子：脱敏、改写输出
            for (AgentHook h : hooks) {
                ToolOutput o = h.afterTool(call, out, ctx);
                if (o != null) {
                    out = o;
                }
            }
            // 子 Agent 等工具会把自己花掉的 token 放在 metadata.usage 里，计入本次运行和会话
            Map<String, Object> meta = new LinkedHashMap<>(out.metadata());
            if (meta.remove("usage") instanceof TokenUsage sub) {
                addUsage(sub);
                conversation.recordUsage(sub, "tool:" + tool.name());
            }
            emit(new AgentEvent.ToolFinished(call, out.text(), out.isError(), elapsed(t0), meta));
            return new ToolResult(call.id(), call.name(), out.content(), out.isError());
        }

        /** 在虚拟线程上执行工具，这样才能对任何工具施加超时和取消（中断执行线程）。 */
        private ToolOutput invoke(Tool tool, Map<String, Object> args, ToolContext ctx) throws Exception {
            Future<ToolOutput> f = EXECUTOR.submit(() -> tool.execute(args, ctx));
            Runnable unregister = ctx.cancel().onCancel(() -> f.cancel(true));
            try {
                Duration timeout = tool.timeout();
                return timeout == null ? f.get() : f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw e;
            } catch (java.util.concurrent.CancellationException e) {
                throw new CancelledException();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw new IllegalStateException(cause);
            } finally {
                unregister.run();
            }
        }

        private ToolResult fail(ToolCall call, long t0, String message) {
            emit(new AgentEvent.ToolFinished(call, message, true, elapsed(t0), Map.of()));
            return ToolResult.text(call.id(), call.name(), message, true);
        }

        private long elapsed(long t0) {
            return (System.nanoTime() - t0) / 1_000_000;
        }

        private ToolOutput truncate(ToolOutput out, int limit) {
            List<ContentBlock> content = out.content().stream()
                    .map(b -> b instanceof TextBlock t ? (ContentBlock) new TextBlock(Texts.truncateMiddle(t.text(), limit)) : b)
                    .toList();
            return out.withContent(content);
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
