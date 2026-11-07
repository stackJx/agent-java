package com.agentcore.domain.agent.service;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.interceptor.ToolInterceptor;
import com.agentcore.domain.agent.interceptor.ToolInvocation;
import com.agentcore.domain.agent.model.EngineSettings;
import com.agentcore.domain.agent.model.RunModel;
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
import java.util.concurrent.CancellationException;
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

        CancellationToken cancelToken();

        RunModel runModel();

        /** 会话级工具状态（文件读取记录、todo 列表等），同一会话的多次运行共用。 */
        Map<String, Object> toolState();

        void emit(AgentEvent event);

        void addUsage(TokenUsage usage);

        void save();
    }

    /** 一批工具调用：parallel 为 true 时并发执行。 */
    private record Batch(boolean parallel, List<ToolCall> calls) {
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
     * 执行全部调用，结果按调用顺序返回。
     * 被取消时，已完成的调用保留真实结果，其余补上 "Cancelled by user."，回填后再把取消抛出去。
     */
    List<ToolResult> execute(List<ToolCall> calls, Scope scope) {
        Map<String, ToolResult> results = new ConcurrentHashMap<>();
        // 并行批次里同时运行的工具数上限（maxToolConcurrency，默认 8）
        Semaphore permits = new Semaphore(Math.max(1, settings.maxToolConcurrency()));
        try {
            for (Batch batch : splitIntoBatches(calls)) {
                scope.cancelToken().throwIfCancelled();
                if (batch.parallel() && batch.calls().size() > 1) {
                    runParallel(batch.calls(), results, permits, scope);
                } else {
                    for (ToolCall call : batch.calls()) {
                        results.put(call.id(), runOne(call, scope));
                    }
                }
            }
        } catch (CancelledException e) {
            // 取消时也要保证每个调用都有结果，历史才合法
            List<ToolResult> filled = calls.stream()
                    .map(call -> results.getOrDefault(call.id(), ToolResult.text(call.id(), call.name(), "Cancelled by user.", true)))
                    .toList();
            scope.conversation().answerToolCalls(filled, List.of());
            scope.save();
            throw e;
        }
        return calls.stream().map(call -> results.get(call.id())).toList();
    }

    /**
     * 切分批次：只把相邻的可并行调用合成一批。
     * 例：[read A, read B, write C, read D] → [A, B]（并行）、[C]、[D]，
     * 保证 C 写之前 A、B 已读完，D 读到的是 C 写之后的内容。
     */
    private List<Batch> splitIntoBatches(List<ToolCall> calls) {
        List<Batch> batches = new ArrayList<>();
        for (ToolCall call : calls) {
            boolean parallel = tools.find(call.name()).map(Tool::concurrencySafe).orElse(false);
            Batch last = batches.isEmpty() ? null : batches.get(batches.size() - 1);
            if (parallel && last != null && last.parallel()) {
                last.calls().add(call);
            } else {
                batches.add(new Batch(parallel, new ArrayList<>(List.of(call))));
            }
        }
        return batches;
    }

    private void runParallel(List<ToolCall> calls, Map<String, ToolResult> results, Semaphore permits, Scope scope) {
        List<Future<?>> futures = new ArrayList<>();
        for (ToolCall call : calls) {
            futures.add(EXECUTOR.submit(() -> {
                permits.acquireUninterruptibly();
                try {
                    results.put(call.id(), runOne(call, scope));
                } finally {
                    permits.release();
                }
                return null;
            }));
        }
        // 取消时中断所有还在跑的工具线程
        Runnable stopAll = () -> futures.forEach(future -> future.cancel(true));
        Runnable unregister = scope.cancelToken().onCancel(stopAll);
        boolean finished = false;
        try {
            for (Future<?> future : futures) {
                waitFor(future);
            }
            finished = true;
        } finally {
            unregister.run();
            if (!finished) {
                stopAll.run(); // 有一个失败了：同一批里其他还在跑的也停掉，不在后台白跑
            }
        }
    }

    private static void waitFor(Future<?> future) {
        try {
            future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CancelledException cancelled) {
                throw cancelled;
            }
            throw new IllegalStateException(e.getCause());
        } catch (CancellationException e) {
            throw new CancelledException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
    }

    /** 执行单个调用。任何一关没通过都不抛异常，而是得到一个错误结果交给模型；只有取消会向上抛。 */
    private ToolResult runOne(ToolCall call, Scope scope) {
        long startedAt = System.nanoTime();
        // 1. 工具存在
        Optional<Tool> tool = tools.find(call.name());
        ToolOutput output = tool.isEmpty()
                ? ToolOutput.error("Error: unknown tool '" + call.name() + "'. Available tools: " + String.join(", ", tools.names()))
                : runChain(new ToolInvocation(call, tool.get(), contextFor(call, scope), scope::emit)); // 2～9. 拦截器链，末端是执行

        Map<String, Object> metadata = takeSubAgentUsage(output, tool.map(Tool::name).orElse(call.name()), scope);
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        scope.emit(new AgentEvent.ToolFinished(call, output.text(), output.isError(), millis, metadata));
        return new ToolResult(call.id(), call.name(), output.content(), output.isError());
    }

    private ToolContext contextFor(ToolCall call, Scope scope) {
        Map<Class<?>, Object> scopedServices = new LinkedHashMap<>(services);
        scopedServices.put(RunModel.class, scope.runModel());
        return new ToolContext(settings.cwd(), scope.conversation().id().value(), call.id(), scope.toolState(),
                progress -> scope.emit(new AgentEvent.ToolProgress(call.id(), progress)), scope.cancelToken(), Map.copyOf(scopedServices), depth);
    }

    /** 子 Agent 等工具会把自己花掉的 token 放在 metadata.usage 里：取出来计入本次运行和会话，返回剩下的 metadata。 */
    private static Map<String, Object> takeSubAgentUsage(ToolOutput output, String toolName, Scope scope) {
        Map<String, Object> metadata = new LinkedHashMap<>(output.metadata());
        if (metadata.remove("usage") instanceof TokenUsage used) {
            scope.addUsage(used);
            scope.conversation().recordUsage(used, "tool:" + toolName);
        }
        return metadata;
    }

    /**
     * 走一遍拦截器链。拦截器、钩子按约定不应该抛异常；万一抛了（使用者代码的 bug），只让这一个调用失败，
     * 错误交给模型，不让它结束整次运行。取消照常向上抛。
     */
    private ToolOutput runChain(ToolInvocation invocation) {
        try {
            return proceed(0, invocation);
        } catch (CancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "tool call " + invocation.tool().name() + " failed inside the interceptor chain", e);
            return ToolOutput.error("Error: the tool call failed before or after execution (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ")");
        }
    }

    /** 拦截器链：第 index 个拦截器处理，它调用 chain.proceed 时进入下一个；走完所有拦截器后执行工具。 */
    private ToolOutput proceed(int index, ToolInvocation invocation) {
        if (index < chain.size()) {
            return chain.get(index).intercept(invocation, next -> proceed(index + 1, next));
        }
        return invoke(invocation);
    }

    /**
     * 7. 执行：在虚拟线程上运行，这样才能对任何工具施加超时和取消（中断执行线程）。
     * ToolException 是"模型可以自己修正的错误"；其他异常、超时也都变成错误结果；取消向上抛。
     */
    private ToolOutput invoke(ToolInvocation invocation) {
        Tool tool = invocation.tool();
        Map<String, Object> args = invocation.arguments();
        ToolContext context = invocation.context();
        invocation.emit(new AgentEvent.ToolStarted(invocation.call(), tool.describe(args)));

        Future<ToolOutput> future = EXECUTOR.submit(() -> tool.execute(args, context));
        Runnable unregister = context.cancel().onCancel(() -> future.cancel(true));
        try {
            Duration timeout = tool.timeout();
            ToolOutput output = timeout == null ? future.get() : future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return output == null ? ToolOutput.text("(no output)") : output;
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolOutput.error("Error: tool timed out after " + tool.timeout().toSeconds() + "s");
        } catch (CancellationException e) {
            throw new CancelledException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (ExecutionException e) {
            return failureOutput(tool, e.getCause(), context);
        } finally {
            unregister.run();
        }
    }

    /** 工具抛了异常：ToolException 原样告诉模型；取消向上抛；其他异常记日志后也变成错误结果。 */
    private static ToolOutput failureOutput(Tool tool, Throwable cause, ToolContext context) {
        if (cause instanceof ToolException) {
            return ToolOutput.error("Error: " + cause.getMessage());
        }
        if (cause instanceof CancelledException cancelled) {
            throw cancelled;
        }
        if (cause instanceof InterruptedException || context.cancel().isCancelled()) {
            throw new CancelledException();
        }
        LOG.log(System.Logger.Level.WARNING, "tool " + tool.name() + " crashed", cause);
        return ToolOutput.error("Error: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }
}
