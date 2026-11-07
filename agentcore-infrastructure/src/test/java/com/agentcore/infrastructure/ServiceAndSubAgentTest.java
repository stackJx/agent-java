package com.agentcore.infrastructure;

import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.call;
import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.reply;
import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.application.AgentService;
import com.agentcore.application.approval.AsyncApprover;
import com.agentcore.application.prompt.SystemPromptBuilder;
import com.agentcore.application.subagent.SubAgentProfile;
import com.agentcore.application.subagent.TaskTool;
import com.agentcore.application.tool.MemoryTools;
import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.model.AgentDefinition;
import com.agentcore.domain.agent.model.EngineSettings;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.agent.service.AgentEngine;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.permission.model.PermissionRequest;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.model.ToolRegistry;
import com.agentcore.domain.tool.schema.Schemas;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.infrastructure.persistence.FileMemoryRepository;
import com.agentcore.infrastructure.persistence.JsonlConversationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceAndSubAgentTest {

    @TempDir
    Path dir;

    static final Tool BIG_READ = FunctionTool.builder("big_read").readOnly(true)
            .schema(Schemas.object().string("name", "", true).build())
            .handler((a, c) -> {
                Thread.sleep(200);
                return ToolOutput.text("data " + a.get("name"));
            }).build();

    @Test
    void subAgentsRunInParallelWithRestrictedToolsAndUsageRollsUp() {
        java.util.function.Function<ModelRequest, com.agentcore.domain.llm.model.ModelResponse> child = q -> {
            List<String> names = q.tools().stream().map(t -> t.name()).toList();
            assertEquals(List.of("big_read"), names); // 白名单 + 禁止递归 task
            assertTrue(q.system().contains("Sub-agent mode"));
            return reply("report for " + q.messages().get(0).text());
        };
        ScriptedModelGateway model = new ScriptedModelGateway()
                .then(reply("", call("task", "description", "A", "prompt", "research A", "agent", "researcher"),
                        call("task", "description", "B", "prompt", "research B", "agent", "researcher")))
                .then(child).then(child)
                .then(reply("merged"));
        TaskTool task = new TaskTool(Map.of("researcher", new SubAgentProfile("researcher", "reads stuff", null, "",
                List.of("big_read"), null, 10)), null);
        AgentEngine engine = AgentEngine.builder()
                .definition(new AgentDefinition("main", "sys", EngineSettings.defaults(dir)))
                .model(model).tools(new ToolRegistry(List.of(BIG_READ, task))).build();
        task.attach(engine);
        AgentService svc = new AgentService(engine, new JsonlConversationRepository(dir.resolve("s")));
        String id = svc.startConversation(Map.of()).id().value();

        List<String> progress = new ArrayList<>();
        RunResult r = svc.chat(id, "go", e -> {
            if (e instanceof AgentEvent.ToolProgress p) {
                progress.add(p.message());
            }
        });
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("merged", r.text());
        List<String> results = svc.find(id).orElseThrow().messages().get(2).toolResults().stream().map(ToolResult::text).sorted().toList();
        assertEquals(List.of("report for research A", "report for research B"), results);
        assertEquals(4, r.usage().requests()); // 父 2 次 + 子 2 次
    }

    @Test
    void selectedModelIsInheritedBySubAgentsAndSavedWithConversation() throws Exception {
        var original = new ScriptedModelGateway().then(reply("startup model must not run"));
        var selected = new ScriptedModelGateway()
                .then(reply("", call("task", "description", "research", "prompt", "child work")))
                .then(request -> {
                    assertTrue(request.system().contains("Sub-agent mode"));
                    return reply("selected child reply");
                })
                .then(reply("selected parent reply"));
        var repository = new JsonlConversationRepository(dir.resolve("selected-sessions"));
        var task = new TaskTool(Map.of(), null);
        var engine = AgentEngine.builder().definition(new AgentDefinition("main", "sys", EngineSettings.defaults(dir)))
                .model(original).tools(new ToolRegistry(List.of(task))).repository(repository).build();
        task.attach(engine);
        var service = new AgentService(engine, repository);
        String id = service.startConversation(Map.of()).id().value();
        var result = service.chatAsync(id, List.of(new TextBlock("go")), null, selected, "selected-profile")
                .result().get(5, TimeUnit.SECONDS);
        assertEquals("selected parent reply", result.text());
        assertFalse(service.isRunning(id), "completion callbacks may immediately start another run");
        var restored = new JsonlConversationRepository(dir.resolve("selected-sessions"))
                .findById(com.agentcore.domain.conversation.model.ConversationId.of(id)).orElseThrow();
        assertEquals("selected-profile", restored.metadata().get("model_id"));
        assertEquals(selected.describe(), restored.metadata().get("model"));
        assertEquals("selected child reply", restored.messages().get(2).toolResults().getFirst().text());
        assertSame(original, engine.model());
        assertEquals(3, result.usage().requests());
    }

    @Test
    void serviceRejectsConcurrentRunsAndSupportsAsyncCancel() throws Exception {
        Tool slow = FunctionTool.builder("slow").readOnly(true).timeout(null).handler((a, c) -> {
            Thread.sleep(10_000);
            return ToolOutput.text("never");
        }).build();
        ScriptedModelGateway model = new ScriptedModelGateway().then(reply("", call("slow")));
        AgentEngine engine = AgentEngine.builder().definition(new AgentDefinition("t", "", EngineSettings.defaults(dir)))
                .model(model).tools(new ToolRegistry(List.of(slow))).build();
        AgentService svc = new AgentService(engine, new JsonlConversationRepository(dir.resolve("s")));
        String id = svc.startConversation(Map.of()).id().value();

        AgentService.RunHandle h = svc.chatAsync(id, List.of(new TextBlock("go")), e -> { });
        Thread.sleep(300);
        assertTrue(svc.isRunning(id));
        assertThrows(IllegalStateException.class, () -> svc.chat(id, "again", e -> { }));
        assertThrows(IllegalStateException.class, () -> svc.delete(id), "正在运行的会话不能删");
        assertEquals(0, svc.deleteNotUpdatedSince(Instant.now().plusSeconds(60)), "保留期限清理会跳过正在运行的会话");
        assertTrue(svc.cancel(id));
        RunResult r = h.result().get();
        assertEquals(StopReason.CANCELLED, r.stopReason());
        assertFalse(svc.isRunning(id));

        // 重新加载后历史合法：工具调用都有结果
        JsonlConversationRepository fresh = new JsonlConversationRepository(dir.resolve("s"));
        assertTrue(fresh.findById(svc.find(id).orElseThrow().id()).orElseThrow().pendingToolCalls().isEmpty());
    }

    @Test
    void systemPromptIncludesProjectInstructionsMemoryAndSections() throws Exception {
        Files.writeString(dir.resolve("AGENTS.md"), "Always answer in haiku.");
        FileMemoryRepository mem = new FileMemoryRepository(dir.resolve("mem"));
        MemoryTools.create(mem).get(0).execute(Map.of("name", "tea", "description", "用户喜欢乌龙茶", "content", "x"), null);
        String p = new SystemPromptBuilder().name("bot").cwd(dir).instructions("用中文").memory(mem).section("# Extra\nhello").build();
        assertTrue(p.startsWith("You are bot"));
        assertTrue(p.contains("Always answer in haiku.") && p.contains("用户喜欢乌龙茶") && p.contains("用中文") && p.endsWith("hello"));
    }

    @Test
    void asyncApprovalEndsAsSoonAsTheRunIsCancelled() throws Exception {
        AsyncApprover approver = new AsyncApprover(Duration.ofMinutes(5));
        approver.attach("c1", request -> { }); // 前端收到了请求，但一直不回
        CancellationToken cancel = new CancellationToken();
        PermissionRequest request = new PermissionRequest("r1", "c1", "bash", Map.of(), "rm -rf build", "bash(rm -rf build)", "tool default");
        CompletableFuture<Approval> answer = CompletableFuture.supplyAsync(() -> approver.approve(request, cancel));
        Thread.sleep(100);
        assertEquals(1, approver.pending("c1").size());
        cancel.cancel();
        assertEquals(Decision.DENY, answer.get(5, TimeUnit.SECONDS).decision(), "不用等满 5 分钟的审批超时");
        assertTrue(approver.pending("c1").isEmpty());
    }

    @Test
    void detachOnlyRemovesTheGivenChannel() {
        AsyncApprover approver = new AsyncApprover(Duration.ofSeconds(1));
        AsyncApprover.Channel first = request -> { };
        AsyncApprover.Channel second = request -> { };
        approver.attach("c1", first);
        approver.attach("c1", second);
        approver.detach("c1", first); // 上一次运行结束时清理自己的通道，不影响已经挂上的新通道
        PermissionRequest request = new PermissionRequest("r1", "c1", "bash", Map.of(), "ls", "bash(ls)", "tool default");
        assertTrue(approver.approve(request).message().contains("timed out"), "通道还在，所以是等到超时，而不是没有前端");
    }
}
