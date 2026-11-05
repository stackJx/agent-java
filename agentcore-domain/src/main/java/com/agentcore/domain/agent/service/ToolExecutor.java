package com.agentcore.domain.agent.service;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.interceptor.ToolInterceptor;
import com.agentcore.domain.agent.interceptor.ToolInvocation;
import com.agentcore.domain.agent.model.EngineSettings;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.cancel.CancelledException;
import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.model.ToolRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行器：执行模型一次回复里的全部工具调用。
 *
 * <ol>
 *   <li>分批：连续的可并行工具（默认等于只读工具）一批并发执行，其余按顺序执行，结果按调用顺序返回；</li>
 *   <li>每个调用：关卡 1 查找工具 → 依次经过拦截器链（关卡 2～6、8、9，见 {@code agent.interceptor}）→ 关卡 7 执行；</li>
 *   <li>收尾：子 Agent 等工具报上来的 token 用量计入本次运行，发出 ToolFinished 事件。</li>
 * </ol>
 * 引擎级对象，无状态，可以被多个运行同时使用；每次运行相关的东西通过 {@link Scope} 传进来。
 */
final class ToolExecutor {

    /** 一次运行提供给执行器的东西（由 AgentEngine 的 Run 实现）。 */
    interface Scope {
        Conversation conversation();

        CancellationToken cancel();

        /** 会话级工具状态（文件读取记录、todo 列表等），同一会话的多次运行共用。 */
        Map<String, Object> toolState();

        void emit(AgentEvent event);

        void addUsage(TokenUsage usage);

        void save();
    }

    private static final System.Logger LOG = System.getLogger(ToolExecutor.class.getName());
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ToolRegistry tools;
    private final List<ToolInterceptor> chain;
    private final EngineSettings settings;
    private final Map<Class<?>, Object> services;
    private final int depth;

    ToolExecutor(ToolRegistry tools, List<ToolInterceptor> chain, EngineSettings settings, Map<Class<?>, Object> services, int depth) {
        this.tools = tools;
        this.chain = List.copyOf(chain);
        this.settings = settings;
        this.services = services;
        this.depth = depth;
    }

    /**
     * 例：[read A, read B, write C, read D] → 批次 [A, B]（并行）、[C]、[D]，
     * 保证 C 写之前 A、B 已读完，D 读到的是 C 写之后的内容。
     * 被取消时，已完成的调用保留真实结果，其余补上 "Cancelled by user."，回填后再把取消抛出去。
     */
    List<ToolResult> execute(List<ToolCall> calls, Scope scope) {
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
        Semaphore permits = new Semaphore(Math.max(1, settings.maxToolConcurrency()));
        try {
            for (int i = 0; i < batches.size(); i++) {
                scope.cancel().throwIfCancelled();
                List<ToolCall> group = batches.get(i);
                if (parallel.get(i) && group.size() > 1) {
                    runParallel(group, results, permits, scope);
                } else {
                    for (ToolCall c : group) {
                        results.put(c.id(), runOne(c, scope));
                    }
                }
            }
        } catch (CancelledException e) {
            // 取消时也要保证每个调用都有结果，历史才合法
            List<ToolResult> filled = calls.stream().map(c -> results.getOrDefault(c.id(),
                    ToolResult.text(c.id(), c.name(), "Cancelled by user.", true))).toList();
            scope.conversation().answerToolCalls(filled, List.of());
            scope.save();
            throw e;
        }
        return calls.stream().map(c -> results.get(c.id())).toList();
    }

    private void runParallel(List<ToolCall> group, Map<String, ToolResult> results, Semaphore permits, Scope scope) {
        List<Future<?>> futures = new ArrayList<>();
        for (ToolCall c : group) {
            futures.add(EXECUTOR.submit(() -> {
                permits.acquireUninterruptibly();
                try {
                    results.put(c.id(), runOne(c, scope));
                } finally {
                    permits.release();
                }
                return null;
            }));
        }
        // 取消时中断所有还在跑的工具线程
        Runnable unregister = scope.cancel().onCancel(() -> futures.forEach(f -> f.cancel(true)));
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
    }

    /** 执行单个调用。任何一关没通过都不抛异常，而是得到一个错误结果交给模型；只有取消会向上抛。 */
    private ToolResult runOne(ToolCall call, Scope scope) {
        long t0 = System.nanoTime();
        // 1. 工具存在
        Optional<Tool> found = tools.find(call.name());
        ToolOutput out;
        String toolName = call.name();
        if (found.isEmpty()) {
            out = ToolOutput.error("Error: unknown tool '" + call.name() + "'. Available tools: " + String.join(", ", tools.names()));
        } else {
            Tool tool = found.get();
            toolName = tool.name();
            ToolContext ctx = new ToolContext(settings.cwd(), scope.conversation().id().value(), call.id(), scope.toolState(),
                    msg -> scope.emit(new AgentEvent.ToolProgress(call.id(), msg)), scope.cancel(), services, depth);
            // 2～9. 拦截器链，末端是执行
            out = proceed(0, new ToolInvocation(call, tool, ctx, scope::emit));
        }
        // 子 Agent 等工具会把自己花掉的 token 放在 metadata.usage 里，计入本次运行和会话
        Map<String, Object> meta = new LinkedHashMap<>(out.metadata());
        if (meta.remove("usage") instanceof TokenUsage sub) {
            scope.addUsage(sub);
            scope.conversation().recordUsage(sub, "tool:" + toolName);
        }
        scope.emit(new AgentEvent.ToolFinished(call, out.text(), out.isError(), (System.nanoTime() - t0) / 1_000_000, meta));
        return new ToolResult(call.id(), call.name(), out.content(), out.isError());
    }

    /** 拦截器链：第 index 个拦截器处理，它调用 chain.proceed 时进入下一个；走完所有拦截器后执行工具。 */
    private ToolOutput proceed(int index, ToolInvocation inv) {
        if (index < chain.size()) {
            return chain.get(index).intercept(inv, next -> proceed(index + 1, next));
        }
        return invoke(inv);
    }

    /**
     * 7. 执行：在虚拟线程上运行，这样才能对任何工具施加超时和取消（中断执行线程）。
     * ToolException 是"模型可以自己修正的错误"；其他异常、超时也都变成错误结果；取消向上抛。
     */
    private ToolOutput invoke(ToolInvocation inv) {
        Tool tool = inv.tool();
        Map<String, Object> args = inv.arguments();
        ToolContext ctx = inv.context();
        inv.emit(new AgentEvent.ToolStarted(inv.call(), tool.describe(args)));
        Future<ToolOutput> f = EXECUTOR.submit(() -> tool.execute(args, ctx));
        Runnable unregister = ctx.cancel().onCancel(() -> f.cancel(true));
        try {
            Duration timeout = tool.timeout();
            ToolOutput out = timeout == null ? f.get() : f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return out == null ? ToolOutput.text("(no output)") : out;
        } catch (TimeoutException e) {
            f.cancel(true);
            return ToolOutput.error("Error: tool timed out after " + tool.timeout().toSeconds() + "s");
        } catch (java.util.concurrent.CancellationException e) {
            throw new CancelledException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolException te) {
                return ToolOutput.error("Error: " + te.getMessage());
            }
            if (cause instanceof CancelledException ce) {
                throw ce;
            }
            if (cause instanceof InterruptedException || ctx.cancel().isCancelled()) {
                throw new CancelledException();
            }
            LOG.log(System.Logger.Level.WARNING, "tool " + tool.name() + " crashed", cause);
            return ToolOutput.error("Error: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } finally {
            unregister.run();
        }
    }
}
