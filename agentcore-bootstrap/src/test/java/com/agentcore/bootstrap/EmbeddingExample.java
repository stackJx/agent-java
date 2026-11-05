package com.agentcore.bootstrap;

import com.agentcore.application.AgentRuntime;
import com.agentcore.domain.agent.AgentEvent;
import com.agentcore.domain.agent.AgentHook;
import com.agentcore.domain.agent.HookDecision;
import com.agentcore.domain.agent.RunResult;
import com.agentcore.domain.conversation.ToolCall;
import com.agentcore.domain.model.ModelGateway;
import com.agentcore.domain.permission.Approval;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolContext;
import com.agentcore.domain.tool.ToolException;
import com.agentcore.infrastructure.config.Settings;
import com.agentcore.infrastructure.llm.ModelGatewayFactory;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.infrastructure.tool.annotation.AgentTool;
import com.agentcore.infrastructure.tool.annotation.AnnotatedTools;
import com.agentcore.infrastructure.tool.annotation.Param;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 把 agentcore 嵌入自己的 Java 应用：自定义业务工具（注解）+ 钩子（业务规则）+ 审批回调 + 事件流。
 *
 * <p>离线运行（脚本化假模型）：直接运行 main。
 * 接真实模型：设置环境变量 AGENTCORE_MODEL=deepseek:deepseek-v4-pro（以及对应的 API Key）。
 */
public final class EmbeddingExample {

    /** 1. 业务工具：普通 Java 方法 + 注解。 */
    public static final class OrderTools {
        private final Map<String, Map<String, Object>> orders = Map.of(
                "A1001", Map.of("status", "shipped", "amount", 199.0),
                "A1002", Map.of("status", "pending", "amount", 58.5));

        @AgentTool(description = "查询订单状态", readOnly = true)
        public Map<String, Object> getOrder(@Param("订单号，例如 A1001") String orderId) {
            Map<String, Object> o = orders.get(orderId);
            if (o == null) {
                throw new ToolException("订单 " + orderId + " 不存在");
            }
            return o;
        }

        public enum Reason { DAMAGED, LATE, OTHER }

        @AgentTool(description = "给订单退款（不可逆操作）")
        public String refund(@Param("订单号") String orderId, @Param("退款原因") Reason reason, @Param("退款金额") double amount) {
            return "已为 " + orderId + " 退款 " + amount + " 元（原因：" + reason + "）";
        }
    }

    /** 2. 钩子：单笔退款超过 100 元转人工。 */
    static final AgentHook REFUND_LIMIT = new AgentHook() {
        @Override
        public HookDecision beforeTool(ToolCall call, Tool tool, Map<String, Object> args, ToolContext ctx) {
            if (call.name().equals("refund") && ((Number) args.get("amount")).doubleValue() > 100) {
                return HookDecision.block("单笔退款超过 100 元需要人工处理，请告知用户已转人工");
            }
            return HookDecision.proceed();
        }
    };

    static ModelGateway offlineModel() {
        return new ScriptedModelGateway()
                .then(ScriptedModelGateway.reply("我先查一下两个订单。",
                        ScriptedModelGateway.call("get_order", "orderId", "A1001"),
                        ScriptedModelGateway.call("get_order", "orderId", "A1002")))
                .then(ScriptedModelGateway.reply("A1002 可以直接退，A1001 金额较大。",
                        ScriptedModelGateway.call("refund", "orderId", "A1001", "reason", "DAMAGED", "amount", 199),
                        ScriptedModelGateway.call("refund", "orderId", "A1002", "reason", "LATE", "amount", 58.5)))
                .then(ScriptedModelGateway.reply("处理结果：A1002 已退款 58.5 元；A1001 金额超过 100 元，已转人工处理。"));
    }

    public static RunResult run(Path cwd, boolean print) {
        Settings settings = Settings.load(null, cwd);
        settings.set("sessions.enabled", false);
        settings.set("memory.enabled", false);
        settings.set("agent.instructions", "你是电商客服助手。退款前先查询订单。用中文回答。");
        String spec = System.getenv("AGENTCORE_MODEL");
        ModelGateway model = spec != null ? new ModelGatewayFactory(settings.mapOfMaps("providers")).create(spec) : offlineModel();

        RuntimeAssembler.Options options = RuntimeAssembler.Options.of(cwd)
                .withModel(model)
                .withExtraTools(AnnotatedTools.from(new OrderTools()))
                .withHooks(List.of(REFUND_LIMIT))
                .withApprover(req -> {                       // 3. 审批回调：这里自动批准，真实场景可接审批流
                    if (print) {
                        System.out.println("   [审批] " + req.summary() + " → 批准");
                    }
                    return Approval.allow();
                });
        try (AgentRuntime rt = RuntimeAssembler.assemble(settings, options)) {
            String id = rt.service().startConversation(Map.of("channel", "example")).id().value();
            return rt.service().chat(id, "订单 A1001 和 A1002 都有问题，帮我退款", e -> {   // 4. 事件流
                if (!print) {
                    return;
                }
                switch (e) {
                    case AgentEvent.TextDelta t -> System.out.print(t.text());
                    case AgentEvent.ToolStarted t -> System.out.println("\n⏺ " + t.summary());
                    case AgentEvent.ToolFinished f -> System.out.println("  ⎿ " + f.output().lines().findFirst().orElse(""));
                    case AgentEvent.RunFinished f -> System.out.println("\n\n[" + f.stopReason().wire() + "] 轮数=" + f.turns());
                    default -> { }
                }
            });
        }
    }

    public static void main(String[] args) {
        run(Path.of("."), true);
    }
}
