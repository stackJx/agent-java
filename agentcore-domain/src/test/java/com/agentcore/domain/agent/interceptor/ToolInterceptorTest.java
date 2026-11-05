package com.agentcore.domain.agent.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.hook.AgentHook;
import com.agentcore.domain.agent.hook.HookDecision;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.service.PermissionPolicy;
import com.agentcore.domain.permission.service.RulePermissionPolicy;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 内置拦截器逐个单独测试：链的末端用一个假的"执行"，记录有没有走到、拿到的参数是什么。 */
class ToolInterceptorTest {

    static final Tool WRITE = FunctionTool.builder("write_thing")
            .schema(Schemas.object().string("name", "", true).integer("count", "", false).build())
            .maxOutputChars(20)
            .handler((a, c) -> ToolOutput.text("unused")).build();

    final List<AgentEvent> events = new ArrayList<>();
    final AtomicReference<Map<String, Object>> reached = new AtomicReference<>();

    ToolInvocation invocation(ToolCall call) {
        ToolContext ctx = new ToolContext(Path.of("."), "conv-1", call.id(), new ConcurrentHashMap<>(), m -> { },
                new CancellationToken(), Map.of(), 0);
        return new ToolInvocation(call, WRITE, ctx, events::add);
    }

    /** 链的末端：记下最终参数，返回指定输出。 */
    ToolInterceptor.Chain terminal(ToolOutput out) {
        return inv -> {
            reached.set(inv.arguments());
            return out;
        };
    }

    @Test
    void argumentsAreValidatedAndCoerced() {
        ArgumentsInterceptor it = new ArgumentsInterceptor();
        ToolOutput bad = it.intercept(invocation(ToolCall.of("c1", "write_thing", Map.of("count", 1))), terminal(ToolOutput.text("ok")));
        assertTrue(bad.isError());
        assertTrue(bad.text().contains("invalid arguments for write_thing"));
        assertNull(reached.get(), "校验失败不会走到执行");

        ToolCall broken = new ToolCall("c2", "write_thing", Map.of(), "{\"name\": ", "invalid JSON arguments");
        assertTrue(it.intercept(invocation(broken), terminal(ToolOutput.text("ok"))).text().contains("invalid tool arguments"));

        ToolOutput ok = it.intercept(invocation(ToolCall.of("c3", "write_thing", Map.of("name", "a", "count", "3"))),
                terminal(ToolOutput.text("ok")));
        assertFalse(ok.isError());
        assertEquals(3, ((Number) reached.get().get("count")).intValue(), "\"3\" 转成了数字");
    }

    @Test
    void permissionDeniesAsksAndApproves() {
        ToolCall call = ToolCall.of("c1", "write_thing", Map.of("name", "a"));
        // 拒绝规则：不询问，直接拒绝
        PermissionInterceptor deny = new PermissionInterceptor(
                RulePermissionPolicy.of("default", null, List.of("write_thing"), null), req -> Approval.allow());
        assertTrue(deny.intercept(invocation(call), terminal(ToolOutput.text("ok"))).text().startsWith("Permission denied"));
        assertNull(reached.get());

        // 需要询问但没有审批人：拒绝
        PermissionInterceptor noApprover = new PermissionInterceptor(PermissionPolicy.defaults(), null);
        assertTrue(noApprover.intercept(invocation(call), terminal(ToolOutput.text("ok"))).text().contains("no approver"));

        // 需要询问，审批人同意：放行，并发出 PermissionDecided 事件
        PermissionInterceptor approve = new PermissionInterceptor(PermissionPolicy.defaults(), req -> Approval.allow());
        assertEquals("ok", approve.intercept(invocation(call), terminal(ToolOutput.text("ok"))).text());
        assertNotNull(reached.get());
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.PermissionDecided d && d.decision().equals("allow")));
    }

    @Test
    void hooksCanBlockRewriteAndPostProcess() {
        AgentHook hook = new AgentHook() {
            @Override
            public HookDecision beforeTool(ToolCall call, Tool tool, Map<String, Object> args, com.agentcore.domain.tool.model.ToolContext ctx) {
                if ("bad".equals(args.get("name"))) {
                    return HookDecision.block("no bad names");
                }
                return HookDecision.rewrite(Map.of("name", "rewritten"));
            }

            @Override
            public ToolOutput afterTool(ToolCall call, ToolOutput output, com.agentcore.domain.tool.model.ToolContext ctx) {
                return ToolOutput.text(output.text().replace("secret", "***"));
            }
        };
        HookInterceptor it = new HookInterceptor(List.of(hook));
        ToolOutput blocked = it.intercept(invocation(ToolCall.of("c1", "write_thing", Map.of("name", "bad"))), terminal(ToolOutput.text("x")));
        assertEquals("Blocked by policy hook: no bad names", blocked.text());
        assertNull(reached.get());

        ToolOutput out = it.intercept(invocation(ToolCall.of("c2", "write_thing", Map.of("name", "a"))), terminal(ToolOutput.text("the secret")));
        assertEquals(Map.of("name", "rewritten"), reached.get());
        assertEquals("the ***", out.text());
    }

    @Test
    void longOutputIsTruncatedInTheMiddle() {
        ToolOutput out = new TruncateInterceptor().intercept(invocation(ToolCall.of("c1", "write_thing", Map.of("name", "a"))),
                terminal(ToolOutput.text("A".repeat(50) + "B".repeat(50))));
        assertTrue(out.text().startsWith("A") && out.text().endsWith("B"));
        assertTrue(out.text().length() < 100);
    }
}
