package com.agentcore.domain.agent.service;

import static com.agentcore.domain.testsupport.FakeModel.call;
import static com.agentcore.domain.testsupport.FakeModel.reply;
import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.hook.AgentHook;
import com.agentcore.domain.agent.hook.HookDecision;
import com.agentcore.domain.agent.interceptor.ToolInterceptor;
import com.agentcore.domain.agent.model.AgentDefinition;
import com.agentcore.domain.agent.model.EngineSettings;
import com.agentcore.domain.agent.model.RunControl;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.context.model.CompactionSettings;
import com.agentcore.domain.context.service.ConversationCompactor;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.MalformedOutputException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.service.PermissionPolicy;
import com.agentcore.domain.testsupport.FakeModel;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.model.ToolRegistry;
import com.agentcore.domain.tool.schema.Schemas;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class AgentEngineTest {

    static final Tool SLOW_READ = FunctionTool.builder("slow_read").readOnly(true)
            .schema(Schemas.object().string("name", "", true).build())
            .handler((a, c) -> {
                Thread.sleep(200);
                return ToolOutput.text("content of " + a.get("name"));
            }).build();

    static final Tool WRITE = FunctionTool.builder("write_thing")
            .schema(Schemas.object().string("name", "", true).build())
            .handler((a, c) -> ToolOutput.text("wrote " + a.get("name"))).build();

    static AgentEngine.Builder engine(FakeModel model, Tool... tools) {
        return AgentEngine.builder()
                .definition(new AgentDefinition("test", "system prompt",
                        EngineSettings.defaults(Path.of(".")).withRetryBaseDelay(Duration.ofMillis(5))))
                .model(model)
                .tools(new ToolRegistry(List.of(tools)));
    }

    static RunResult run(AgentEngine e, Conversation c, String input, List<AgentEvent> sink) {
        return e.run(c, List.of(new TextBlock(input)), sink::add, new RunControl());
    }

    @Test
    void basicToolLoop() {
        FakeModel m = new FakeModel().then(reply("先看看", call("slow_read", "name", "a"))).then(reply("内容是 content of a"));
        Conversation c = Conversation.start();
        List<AgentEvent> events = new ArrayList<>();
        RunResult r = run(engine(m, SLOW_READ).build(), c, "读 a", events);
        assertTrue(r.ok());
        assertEquals("内容是 content of a", r.text());
        assertEquals(List.of("USER", "ASSISTANT", "USER", "ASSISTANT"), c.messages().stream().map(x -> x.role().name()).toList());
        assertEquals("content of a", c.messages().get(2).toolResults().get(0).text());
        assertInstanceOf(AgentEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AgentEvent.RunFinished.class, events.get(events.size() - 1));
        assertEquals(c.messages().get(1).toolCalls().get(0).id(),
                m.requests.get(1).messages().get(2).toolResults().get(0).toolCallId());
    }

    @Test
    void readOnlyToolsRunInParallelAndKeepOrder() {
        ToolCall[] calls = new ToolCall[5];
        for (int i = 0; i < 5; i++) {
            calls[i] = call("slow_read", "name", String.valueOf(i));
        }
        FakeModel m = new FakeModel().then(reply("", calls)).then(reply("done"));
        Conversation c = Conversation.start();
        long t0 = System.currentTimeMillis();
        RunResult r = run(engine(m, SLOW_READ).build(), c, "go", new ArrayList<>());
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(r.ok());
        assertTrue(elapsed < 700, "5 个只读工具应并行执行，耗时 " + elapsed + "ms");
        assertEquals(List.of("content of 0", "content of 1", "content of 2", "content of 3", "content of 4"),
                c.messages().get(2).toolResults().stream().map(ToolResult::text).toList());
    }

    @Test
    void errorsAreReturnedToTheModel() {
        ToolCall missingArg = ToolCall.of("x1", "slow_read", Map.of());
        ToolCall broken = new ToolCall("x2", "slow_read", Map.of(), "{bad", "invalid JSON");
        FakeModel m = new FakeModel().then(reply("", call("nope"), missingArg, broken)).then(reply("ok"));
        Conversation c = Conversation.start();
        assertTrue(run(engine(m, SLOW_READ).build(), c, "go", new ArrayList<>()).ok());
        List<ToolResult> r = c.messages().get(2).toolResults();
        assertTrue(r.stream().allMatch(ToolResult::isError));
        assertTrue(r.get(0).text().contains("unknown tool 'nope'"));
        assertTrue(r.get(1).text().contains("missing required property 'name'"));
        assertTrue(r.get(2).text().contains("invalid tool arguments"));
    }

    @Test
    void permissionAskWithoutApproverIsDenied() {
        FakeModel m = new FakeModel().then(reply("", call("write_thing", "name", "x"))).then(reply("ok"));
        Conversation c = Conversation.start();
        run(engine(m, WRITE).build(), c, "go", new ArrayList<>());
        ToolResult r = c.messages().get(2).toolResults().get(0);
        assertTrue(r.isError() && r.text().contains("Permission denied"));
    }

    @Test
    void approverAlwaysIsRemembered() {
        List<String> asked = new CopyOnWriteArrayList<>();
        FakeModel m = new FakeModel().then(reply("", call("write_thing", "name", "a")))
                .then(reply("", call("write_thing", "name", "b"))).then(reply("ok"));
        Conversation c = Conversation.start();
        AgentEngine e = engine(m, WRITE).permissions(PermissionPolicy.defaults()).approver(req -> {
            asked.add(req.tool());
            return Approval.always();
        }).build();
        run(e, c, "go", new ArrayList<>());
        assertEquals(List.of("write_thing"), asked);
        assertEquals("wrote b", c.messages().get(4).toolResults().get(0).text());
    }

    @Test
    void customPolicyCanReplaceRules() {
        // 自定义策略（例如对接公司的权限系统）：name=secret 直接拒绝，其他都要审批；没有实现 remember，"总是允许"不会被记住
        PermissionPolicy policy = (tool, args) -> "secret".equals(args.get("name"))
                ? PermissionPolicy.Verdict.deny("tenant policy forbids secret")
                : PermissionPolicy.Verdict.ask("tenant policy requires approval");
        List<String> asked = new CopyOnWriteArrayList<>();
        FakeModel m = new FakeModel().then(reply("", call("write_thing", "name", "secret")))
                .then(reply("", call("write_thing", "name", "a")))
                .then(reply("", call("write_thing", "name", "b"))).then(reply("ok"));
        Conversation c = Conversation.start();
        AgentEngine e = engine(m, WRITE).permissions(policy).approver(req -> {
            asked.add(req.reason());
            return Approval.always();
        }).build();
        run(e, c, "go", new ArrayList<>());
        assertTrue(c.messages().get(2).toolResults().get(0).text().contains("tenant policy forbids secret"));
        assertEquals(List.of("tenant policy requires approval", "tenant policy requires approval"), asked); // 第二次照样询问
        assertEquals("wrote b", c.messages().get(6).toolResults().get(0).text());
    }

    @Test
    void customInterceptorRunsAfterPermissionAndCanBlock() {
        // 使用者的拦截器：排在权限审批之后，看到的是已批准、已校验的调用；可以拦下，也可以记录
        List<String> seen = new CopyOnWriteArrayList<>();
        ToolInterceptor sandbox = (inv, chain) -> {
            seen.add(inv.tool().name() + ":" + inv.arguments().get("name"));
            if ("/etc/passwd".equals(inv.arguments().get("name"))) {
                return ToolOutput.error("Blocked by sandbox: outside workspace");
            }
            return chain.proceed(inv);
        };
        FakeModel m = new FakeModel().then(reply("", call("write_thing", "name", "/etc/passwd")))
                .then(reply("", call("write_thing", "name", "ok.txt"))).then(reply("done"));
        Conversation c = Conversation.start();
        AgentEngine e = engine(m, WRITE).approver(req -> Approval.allow()).interceptor(sandbox).build();
        run(e, c, "go", new ArrayList<>());
        assertEquals(List.of("write_thing:/etc/passwd", "write_thing:ok.txt"), seen);
        assertEquals("Blocked by sandbox: outside workspace", c.messages().get(2).toolResults().get(0).text());
        assertEquals("wrote ok.txt", c.messages().get(4).toolResults().get(0).text());
        assertEquals(List.of(sandbox), e.toBuilder().build().interceptors(), "toBuilder（子 Agent）会带上拦截器");
    }

    @Test
    void denialFeedbackReachesModel() {
        FakeModel m = new FakeModel().then(reply("", call("write_thing", "name", "a"))).then(reply("ok"));
        Conversation c = Conversation.start();
        run(engine(m, WRITE).approver(req -> Approval.deny("用 git stash 代替")).build(), c, "go", new ArrayList<>());
        assertTrue(c.messages().get(2).toolResults().get(0).text().contains("用 git stash 代替"));
    }

    @Test
    void hooksCanBlockRewriteAndRedact() {
        AgentHook hook = new AgentHook() {
            @Override
            public HookDecision beforeTool(ToolCall call, Tool tool, Map<String, Object> args, ToolContext ctx) {
                if ("bad".equals(args.get("name"))) {
                    return HookDecision.block("not allowed");
                }
                return HookDecision.rewrite(Map.of("name", String.valueOf(args.get("name")).toUpperCase()));
            }

            @Override
            public ToolOutput afterTool(ToolCall call, ToolOutput out, ToolContext ctx) {
                return ToolOutput.text(out.text().replace("A", "*"));
            }
        };
        FakeModel m = new FakeModel().then(reply("", call("slow_read", "name", "bad"), call("slow_read", "name", "a"))).then(reply("ok"));
        Conversation c = Conversation.start();
        run(engine(m, SLOW_READ).hook(hook).build(), c, "go", new ArrayList<>());
        List<ToolResult> r = c.messages().get(2).toolResults();
        assertTrue(r.get(0).text().contains("Blocked by policy hook: not allowed"));
        assertEquals("content of *", r.get(1).text());
    }

    @Test
    void truncatedToolCallIsNeverExecuted() {
        List<String> executed = new CopyOnWriteArrayList<>();
        Tool probe = FunctionTool.builder("probe").readOnly(true).schema(Schemas.object().string("x", "", true).build())
                .handler((a, ctx) -> {
                    executed.add((String) a.get("x"));
                    return ToolOutput.text("ran");
                }).build();
        FakeModel m = new FakeModel().then(reply("", StopReason.MAX_TOKENS, call("probe", "x", "half"))).then(reply("ok"));
        Conversation c = Conversation.start();
        assertTrue(run(engine(m, probe).build(), c, "go", new ArrayList<>()).ok());
        assertTrue(executed.isEmpty());
        assertTrue(c.messages().get(2).toolResults().get(0).text().contains("cut off"));
    }

    @Test
    void pauseTurnRefusalAndMaxTurns() {
        FakeModel m = new FakeModel().then(reply("partial", StopReason.PAUSE_TURN)).then(reply("final"));
        RunResult r = run(engine(m).build(), Conversation.start(), "go", new ArrayList<>());
        assertEquals("final", r.text());
        assertEquals(2, r.turns());

        FakeModel refuse = new FakeModel().then(reply("", StopReason.REFUSAL));
        assertEquals(StopReason.REFUSAL, run(engine(refuse).build(), Conversation.start(), "go", new ArrayList<>()).stopReason());

        FakeModel loop = new FakeModel().otherwise(q -> reply("", call("slow_read", "name", "x")));
        AgentEngine e = engine(loop, SLOW_READ).definition(new AgentDefinition("t", "",
                EngineSettings.defaults(Path.of(".")).withMaxTurns(3))).build();
        RunResult mr = run(e, Conversation.start(), "go", new ArrayList<>());
        assertEquals(StopReason.MAX_TURNS, mr.stopReason());
        assertEquals(3, mr.turns());
    }

    @Test
    void retriesRetryableAndMalformedErrors() {
        FakeModel m = new FakeModel().thenThrow(new ModelException("rate limited", true))
                .thenThrow(new MalformedOutputException("bad json", null)).then(reply("ok"));
        List<AgentEvent> events = new ArrayList<>();
        RunResult r = run(engine(m).build(), Conversation.start(), "go", events);
        assertTrue(r.ok());
        assertTrue(events.stream().anyMatch(AgentEvent.Retrying.class::isInstance));

        FakeModel bad = new FakeModel().thenThrow(new ModelException("bad key", false));
        RunResult br = run(engine(bad).build(), Conversation.start(), "go", new ArrayList<>());
        assertEquals(StopReason.ERROR, br.stopReason());
        assertTrue(br.error().contains("bad key"));
    }

    @Test
    void cancelKeepsHistoryValid() throws Exception {
        Tool forever = FunctionTool.builder("forever").readOnly(true).timeout(null).handler((a, c) -> {
            Thread.sleep(30_000);
            return ToolOutput.text("never");
        }).build();
        FakeModel m = new FakeModel().then(reply("", call("forever")));
        Conversation c = Conversation.start();
        RunControl control = new RunControl();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // ignore
            }
            control.cancel();
        });
        RunResult r = engine(m, forever).build().run(c, List.of(new TextBlock("go")), e -> { }, control);
        assertEquals(StopReason.CANCELLED, r.stopReason());
        Message last = c.messages().get(c.size() - 1);
        assertTrue(last.toolResults().get(0).isError());
        assertTrue(c.pendingToolCalls().isEmpty());
    }

    @Test
    void steeringIsInjectedAtTurnBoundary() {
        FakeModel m = new FakeModel().then(reply("", call("slow_read", "name", "a"))).then(reply("done"));
        Conversation c = Conversation.start();
        RunControl control = new RunControl();
        engine(m, SLOW_READ).build().run(c, List.of(new TextBlock("go")), e -> {
            if (e instanceof AgentEvent.ToolStarted) {
                control.steer("顺便也看看 b");
            }
        }, control);
        assertEquals("顺便也看看 b", m.requests.get(1).messages().get(2).text());
    }

    @Test
    void autoCompactionReplacesHistory() {
        Tool big = FunctionTool.builder("big").readOnly(true).handler((a, ctx) -> ToolOutput.text("x".repeat(4000))).build();
        FakeModel m = new FakeModel(2000)
                .then(reply("", call("big")))
                .then(reply("", call("big")))
                .then(q -> {
                    assertEquals(ConversationCompactor.SUMMARY_SYSTEM, q.system());
                    assertTrue(q.tools().isEmpty());
                    return reply("SUMMARY: user wants X");
                })
                .then(q -> reply(q.messages().get(0).text().contains("SUMMARY") ? "continued" : "WRONG"));
        Conversation c = Conversation.start();
        List<AgentEvent> events = new ArrayList<>();
        RunResult r = run(engine(m, big).compactor(new ConversationCompactor(new CompactionSettings(0.75, 0, 1000, 10_000))).build(),
                c, "do X", events);
        assertEquals("continued", r.text());
        assertTrue(events.stream().anyMatch(AgentEvent.Compacted.class::isInstance));
        assertTrue(c.messages().get(0).text().startsWith("<conversation_summary>"));
        assertEquals(1, c.compactions());
    }

    @Test
    void requestOverflowCompactsAndRetriesTheSameTurn() {
        FakeModel m = new FakeModel()
                .then(reply("第一次回答"))
                .thenThrow(new ContextOverflowException("prompt is too long", null))
                .then(reply("SUMMARY: 之前问过第一个问题")) // 压缩时请求的摘要
                .then(q -> reply(q.messages().get(0).text().contains("SUMMARY") ? "压缩后继续" : "WRONG"));
        AgentEngine e = engine(m).build();
        Conversation c = Conversation.start();
        run(e, c, "第一个问题", new ArrayList<>());
        List<AgentEvent> events = new ArrayList<>();
        RunResult r = run(e, c, "第二个问题", events);
        assertEquals("压缩后继续", r.text());
        assertEquals(1, r.turns(), "压缩后重发属于同一轮");
        assertEquals(1, c.compactions());
        assertTrue(events.stream().anyMatch(AgentEvent.Compacted.class::isInstance));
    }
}
