package com.agentcore.infrastructure;

import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.call;
import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.reply;
import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.application.AgentService;
import com.agentcore.application.prompt.SystemPromptBuilder;
import com.agentcore.application.subagent.SubAgentProfile;
import com.agentcore.application.subagent.TaskTool;
import com.agentcore.application.tool.MemoryTools;
import com.agentcore.domain.agent.AgentDefinition;
import com.agentcore.domain.agent.AgentEngine;
import com.agentcore.domain.agent.AgentEvent;
import com.agentcore.domain.agent.EngineSettings;
import com.agentcore.domain.agent.RunResult;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.domain.conversation.ToolResult;
import com.agentcore.domain.model.ModelRequest;
import com.agentcore.domain.model.StopReason;
import com.agentcore.domain.tool.FunctionTool;
import com.agentcore.domain.tool.Schemas;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolOutput;
import com.agentcore.domain.tool.ToolRegistry;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.infrastructure.persistence.FileMemoryRepository;
import com.agentcore.infrastructure.persistence.JsonlConversationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        java.util.function.Function<ModelRequest, com.agentcore.domain.model.ModelResponse> child = q -> {
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
}
