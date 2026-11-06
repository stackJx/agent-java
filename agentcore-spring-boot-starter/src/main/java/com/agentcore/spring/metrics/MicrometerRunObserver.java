package com.agentcore.spring.metrics;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.event.AgentEventListener;
import com.agentcore.domain.agent.event.RunObserver;
import com.agentcore.domain.conversation.model.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 用 Micrometer 记录 Agent 的运行指标。应用里有 MeterRegistry 时生效（例如引入了 spring-boot-starter-actuator），
 * 访问 {@code /actuator/metrics/agentcore.runs} 等即可查看，也会跟着应用一起导出到 Prometheus 等监控系统。
 *
 * <table>
 *   <caption>指标</caption>
 *   <tr><th>名称</th><th>类型</th><th>标签</th><th>含义</th></tr>
 *   <tr><td>agentcore.runs</td><td>计时器</td><td>agent, stop_reason</td><td>一次运行的总耗时（次数即运行数）</td></tr>
 *   <tr><td>agentcore.model.calls</td><td>计时器</td><td>model, stop_reason</td><td>一次模型调用的耗时（含重试等待）</td></tr>
 *   <tr><td>agentcore.model.tokens</td><td>计数器</td><td>model, type</td><td>token 用量，type 为 input / output / cache_read / cache_write</td></tr>
 *   <tr><td>agentcore.model.retries</td><td>计数器</td><td>model</td><td>模型调用的重试次数</td></tr>
 *   <tr><td>agentcore.tool.calls</td><td>计时器</td><td>tool, outcome</td><td>工具调用耗时，outcome 为 success / error</td></tr>
 *   <tr><td>agentcore.approvals</td><td>计时器</td><td>tool, decision</td><td>等审批人回答的时间，decision 为 allow / deny</td></tr>
 * </table>
 * token 按每次模型调用累加（子 Agent 的调用也算在它自己的 model 下），所以不会重复计算。
 */
public final class MicrometerRunObserver implements RunObserver {

    private static final String UNKNOWN = "unknown";

    private final ObjectProvider<MeterRegistry> registry;

    /** 用 ObjectProvider：MeterRegistry 由 Actuator 创建，第一次运行时才去取，不依赖自动装配的先后顺序。 */
    public MicrometerRunObserver(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public AgentEventListener startRun(String conversationId) {
        MeterRegistry meters = registry.getIfAvailable();
        return meters == null ? AgentEventListener.NOOP : new RunMetrics(meters);
    }

    /** 一次运行的指标：记下开始时间、Agent 名和模型名，事件到来时更新计量器。引擎发事件时加了锁，这里不需要同步。 */
    private static final class RunMetrics implements AgentEventListener {

        private final MeterRegistry meters;
        private final long runStartedAt = System.nanoTime();
        private long modelCallStartedAt = System.nanoTime();
        private String agent = UNKNOWN;
        private String model = UNKNOWN;

        RunMetrics(MeterRegistry meters) {
            this.meters = meters;
        }

        @Override
        public void onEvent(AgentEvent event) {
            switch (event) {
                case AgentEvent.RunStarted started -> {
                    agent = started.agent();
                    model = started.model();
                }
                // 模型调用从这一轮开始算；先压缩了上下文的话，从压缩完成算起
                case AgentEvent.TurnStarted turn -> modelCallStartedAt = System.nanoTime();
                case AgentEvent.Compacted compacted -> modelCallStartedAt = System.nanoTime();
                case AgentEvent.AssistantMessageCompleted reply -> recordModelCall(reply);
                case AgentEvent.Retrying retrying -> counter("agentcore.model.retries", "model", model).increment();
                case AgentEvent.ToolFinished tool -> recordToolCall(tool);
                case AgentEvent.PermissionDecided decided -> recordApproval(decided);
                case AgentEvent.RunFinished finished -> recordRun(finished);
                default -> { }
            }
        }

        private void recordModelCall(AgentEvent.AssistantMessageCompleted reply) {
            timer("agentcore.model.calls", "model", model, "stop_reason", String.valueOf(reply.stopReason()))
                    .record(Duration.ofNanos(System.nanoTime() - modelCallStartedAt));
            TokenUsage usage = reply.usage();
            if (usage != null) {
                addTokens("input", usage.inputTokens());
                addTokens("output", usage.outputTokens());
                addTokens("cache_read", usage.cacheReadTokens());
                addTokens("cache_write", usage.cacheWriteTokens());
            }
        }

        private void addTokens(String type, long amount) {
            if (amount > 0) {
                counter("agentcore.model.tokens", "model", model, "type", type).increment(amount);
            }
        }

        private void recordToolCall(AgentEvent.ToolFinished tool) {
            timer("agentcore.tool.calls", "tool", tool.call().name(), "outcome", tool.isError() ? "error" : "success")
                    .record(Duration.ofMillis(tool.durationMillis()));
        }

        private void recordApproval(AgentEvent.PermissionDecided decided) {
            timer("agentcore.approvals", "tool", decided.tool(), "decision", decided.decision())
                    .record(Duration.ofMillis(decided.waitMillis()));
        }

        private void recordRun(AgentEvent.RunFinished finished) {
            timer("agentcore.runs", "agent", agent, "stop_reason", String.valueOf(finished.stopReason()))
                    .record(Duration.ofNanos(System.nanoTime() - runStartedAt));
        }

        private Timer timer(String name, String... tags) {
            return Timer.builder(name).tags(tags).register(meters);
        }

        private Counter counter(String name, String... tags) {
            return Counter.builder(name).tags(tags).register(meters);
        }
    }
}
