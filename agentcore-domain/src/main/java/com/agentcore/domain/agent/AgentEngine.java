package com.agentcore.domain.agent;

import com.agentcore.domain.context.ConversationCompactor;
import com.agentcore.domain.context.TokenEstimator;
import com.agentcore.domain.conversation.ContentBlock;
import com.agentcore.domain.conversation.Conversation;
import com.agentcore.domain.conversation.ConversationRepository;
import com.agentcore.domain.conversation.Message;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.domain.conversation.TokenUsage;
import com.agentcore.domain.conversation.ToolCall;
import com.agentcore.domain.conversation.ToolResult;
import com.agentcore.domain.model.ContextOverflowException;
import com.agentcore.domain.model.MalformedOutputException;
import com.agentcore.domain.model.ModelException;
import com.agentcore.domain.model.ModelGateway;
import com.agentcore.domain.model.ModelRequest;
import com.agentcore.domain.model.ModelResponse;
import com.agentcore.domain.model.ModelStreamListener;
import com.agentcore.domain.model.StopReason;
import com.agentcore.domain.permission.Approval;
import com.agentcore.domain.permission.Approver;
import com.agentcore.domain.permission.Decision;
import com.agentcore.domain.permission.PermissionPolicy;
import com.agentcore.domain.permission.PermissionRequest;
import com.agentcore.domain.permission.PermissionRule;
import com.agentcore.domain.shared.CancelledException;
import com.agentcore.domain.shared.Texts;
import com.agentcore.domain.tool.SchemaValidator;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolContext;
import com.agentcore.domain.tool.ToolException;
import com.agentcore.domain.tool.ToolOutput;
import com.agentcore.domain.tool.ToolRegistry;
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
    private final Map<String, Map<String, Object>> toolState = new ConcurrentHashMap<>();
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
                if (conversation.repairDanglingToolCalls("Interrupted: the previous run ended before this tool finished.")) {
                    save();
                }
                if (input != null && !input.isEmpty()) {
                    conversation.addUserInput(input);
                    save();
                }
                emit(new AgentEvent.RunStarted(conversation.id().value(), definition.name(), model.describe()));
                while (true) {
                    control.token().throwIfCancelled();
                    if (turn >= s.maxTurns()) {
                        stop = StopReason.MAX_TURNS;
                        emit(new AgentEvent.Notice("warning", "reached max_turns=" + s.maxTurns()));
                        break;
                    }
                    turn++;
                    emit(new AgentEvent.TurnStarted(turn));

                    if (forceCompact || compactor.shouldCompact(estimateContextTokens(conversation), model.contextWindow())) {
                        forceCompact = false;
                        compact();
                    }

                    ModelRequest request = ModelRequest.of(definition.systemPrompt(), conversation.messages(), tools.specs());
                    for (AgentHook h : hooks) {
                        request = h.beforeModel(request);
                    }
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

                    Message msg = response.message();
                    conversation.addAssistantReply(msg, response.usage());
                    save();
                    addUsage(response.usage());
                    emit(new AgentEvent.AssistantMessageCompleted(msg, response.usage(), response.stopReason()));

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

        private ModelResponse callModel(ModelRequest request) {
            int malformed = 0;
            int attempt = 0;
            EngineSettings s = definition.settings();
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

        /** 连续的可并行工具一起并发执行，其余按顺序执行；结果保持调用顺序。 */
        private List<ToolResult> executeTools(List<ToolCall> calls) {
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

        private ToolResult runTool(ToolCall call) {
            long t0 = System.nanoTime();
            Optional<Tool> found = tools.find(call.name());
            if (found.isEmpty()) {
                return fail(call, t0, "Error: unknown tool '" + call.name() + "'. Available tools: " + String.join(", ", tools.names()));
            }
            Tool tool = found.get();
            if (call.parseError() != null) {
                return fail(call, t0, "Error: invalid tool arguments: " + call.parseError()
                        + ". Send a valid JSON object matching the schema.");
            }
            SchemaValidator.Result vr = SchemaValidator.validate(call.arguments(), tool.inputSchema());
            if (!vr.valid()) {
                return fail(call, t0, "Error: invalid arguments for " + tool.name() + ":\n- "
                        + String.join("\n- ", vr.errors().subList(0, Math.min(10, vr.errors().size()))));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> args = vr.value() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

            // ---- 权限
            PermissionPolicy.Verdict verdict = permissions.check(tool, args);
            Decision decision = verdict.decision();
            String reason = verdict.reason();
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
                    if (approval.decision() == Decision.ALLOW && approval.remember()) {
                        PermissionRule rule = permissions.remember(tool, args);
                        reason = "always allowed (" + rule + ")";
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
            for (AgentHook h : hooks) {
                HookDecision hd = h.beforeTool(call, tool, args, ctx);
                if (hd != null && hd.blocked()) {
                    return fail(call, t0, "Blocked by policy hook: " + hd.message());
                }
                if (hd != null && hd.rewrittenArguments() != null) {
                    args = hd.rewrittenArguments();
                }
            }

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
            out = truncate(out, tool.maxOutputChars());
            for (AgentHook h : hooks) {
                ToolOutput o = h.afterTool(call, out, ctx);
                if (o != null) {
                    out = o;
                }
            }
            Map<String, Object> meta = new LinkedHashMap<>(out.metadata());
            if (meta.remove("usage") instanceof TokenUsage sub) {
                addUsage(sub);
                conversation.recordUsage(sub, "tool:" + tool.name());
            }
            emit(new AgentEvent.ToolFinished(call, out.text(), out.isError(), elapsed(t0), meta));
            return new ToolResult(call.id(), call.name(), out.content(), out.isError());
        }

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

        void compact() {
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
