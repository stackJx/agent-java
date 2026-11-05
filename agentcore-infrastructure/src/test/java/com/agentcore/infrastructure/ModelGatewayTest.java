package com.agentcore.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.agent.AgentDefinition;
import com.agentcore.domain.agent.AgentEngine;
import com.agentcore.domain.agent.AgentEvent;
import com.agentcore.domain.agent.EngineSettings;
import com.agentcore.domain.agent.RunControl;
import com.agentcore.domain.agent.RunResult;
import com.agentcore.domain.conversation.Conversation;
import com.agentcore.domain.conversation.ImageBlock;
import com.agentcore.domain.conversation.Message;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.domain.conversation.ThinkingBlock;
import com.agentcore.domain.conversation.ToolCall;
import com.agentcore.domain.conversation.ToolResult;
import com.agentcore.domain.model.ModelGateway;
import com.agentcore.domain.model.ModelRequest;
import com.agentcore.domain.model.ModelResponse;
import com.agentcore.domain.model.StopReason;
import com.agentcore.domain.tool.FunctionTool;
import com.agentcore.domain.tool.Schemas;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolOutput;
import com.agentcore.domain.tool.ToolRegistry;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.llm.ModelGatewayFactory;
import com.agentcore.infrastructure.llm.anthropic.AnthropicModelGateway;
import com.agentcore.infrastructure.llm.anthropic.AnthropicWire;
import com.agentcore.infrastructure.llm.openai.OpenAiCompatibleModelGateway;
import com.agentcore.infrastructure.llm.openai.OpenAiWire;
import com.agentcore.infrastructure.persistence.InMemoryConversationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 通过本地假服务端，走真实的 Anthropic SDK / OpenAI 兼容解析路径。 */
class ModelGatewayTest {

    static final Tool LOOKUP = FunctionTool.builder("lookup").readOnly(true)
            .schema(Schemas.object().string("key", "", true).build())
            .handler((a, c) -> ToolOutput.text("value-of-" + a.get("key"))).build();

    static RunResult runAgent(ModelGateway model, Conversation c, String input, List<AgentEvent> events) {
        AgentEngine e = AgentEngine.builder()
                .definition(new AgentDefinition("t", "you are a test agent", EngineSettings.defaults(Path.of("."))))
                .model(model).tools(new ToolRegistry(List.of(LOOKUP))).repository(new InMemoryConversationRepository()).build();
        return e.run(c, List.of(new TextBlock(input)), events::add, new RunControl());
    }

    static ObjectNode block(String type, Object... kv) {
        ObjectNode n = Json.object().put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            n.set(String.valueOf(kv[i]), Json.tree(kv[i + 1]));
        }
        return n;
    }

    @Test
    void anthropicSdkStreamingRoundTrip() throws Exception {
        try (FakeSseServer srv = new FakeSseServer()) {
            srv.respond(FakeSseServer.anthropicMessage(List.of(
                    block("thinking", "thinking", "Let me look it up", "signature", "sig-123"),
                    block("text", "text", "Checking."),
                    block("tool_use", "id", "toolu_1", "name", "lookup", "input", Map.of("key", "x"))), "tool_use", 50, 30, 400));
            srv.respond(FakeSseServer.anthropicMessage(List.of(block("text", "text", "It is value-of-x.")), "end_turn", 60, 10, 450));

            AnthropicModelGateway gw = new AnthropicModelGateway("claude-opus-5-5", new AnthropicModelGateway.Options(
                    "test-key", srv.url(), null, null, null, null, null, null, true, null, 0, null, Map.of()));
            assertFalse(gw.fallbacksEnabled(), "自定义 base_url（代理）时默认不开 beta 回退");
            Conversation c = Conversation.start();
            List<AgentEvent> events = new ArrayList<>();
            RunResult r = runAgent(gw, c, "what is x?", events);
            assertTrue(r.ok(), String.valueOf(r.error()));
            assertEquals("It is value-of-x.", r.text());
            String streamed = events.stream().map(e -> e instanceof AgentEvent.TextDelta t ? t.text()
                    : e instanceof AgentEvent.ThinkingDelta t ? t.text() : "").reduce("", String::concat);
            assertEquals("Let me look it upChecking.It is value-of-x.", streamed);

            Message a = c.messages().get(1);
            assertEquals(Map.of("key", "x"), a.toolCalls().get(0).arguments());
            assertEquals("Let me look it up", a.thinking());
            assertTrue(a.providerPayload().rawContentJson().contains("sig-123"));
            assertEquals(850, r.usage().cacheReadTokens()); // 两次请求的缓存命中（400 + 450）累计

            JsonNode req1 = srv.requests.get(0).body();
            assertEquals("claude-opus-5-5", req1.path("model").asText());
            assertEquals("adaptive", req1.path("thinking").path("type").asText());
            assertEquals("summarized", req1.path("thinking").path("display").asText());
            assertEquals("high", req1.path("output_config").path("effort").asText());
            assertEquals("ephemeral", req1.path("system").get(0).path("cache_control").path("type").asText());
            assertEquals("ephemeral", req1.path("cache_control").path("type").asText());
            assertTrue(req1.path("tools").get(0).path("eager_input_streaming").asBoolean());
            assertTrue(req1.path("stream").asBoolean());
            assertFalse(req1.has("temperature"));

            JsonNode req2 = srv.requests.get(1).body();
            JsonNode replay = req2.path("messages").get(1);
            assertEquals("assistant", replay.path("role").asText());
            assertEquals("thinking", replay.path("content").get(0).path("type").asText());
            assertEquals("sig-123", replay.path("content").get(0).path("signature").asText());
            JsonNode tr = req2.path("messages").get(2).path("content").get(0);
            assertEquals("tool_result", tr.path("type").asText());
            assertEquals("toolu_1", tr.path("tool_use_id").asText());
            assertEquals("value-of-x", tr.path("content").asText());
        }
    }

    @Test
    void anthropicFallbacksUseBetaHeader() throws Exception {
        try (FakeSseServer srv = new FakeSseServer()) {
            srv.respond(FakeSseServer.anthropicMessage(List.of(block("text", "text", "hi")), "end_turn", 5, 1, 0));
            AnthropicModelGateway gw = new AnthropicModelGateway("claude-opus-5-5", new AnthropicModelGateway.Options(
                    "k", srv.url(), null, null, null, null, null, true, null, null, 0, null, Map.of()));
            ModelResponse resp = gw.complete(ModelRequest.of("s", List.of(Message.user("hi")), List.of()));
            assertEquals("hi", resp.message().text());
            assertEquals(StopReason.END_TURN, resp.stopReason());
            assertEquals("default", srv.requests.get(0).body().path("fallbacks").asText());
            assertTrue(srv.requests.get(0).headers().getOrDefault("anthropic-beta", "").contains("server-side-fallback-2026-07-01"));
        }
    }

    @Test
    void fallbackEchoRule() {
        List<JsonNode> blocks = List.of(
                block("thinking", "signature", "s1"), block("text", "text", "partial"), block("tool_use", "id", "t1"),
                block("server_tool_use", "id", "s1"), block("server_tool_use", "id", "s2"),
                block("web_search_tool_result", "tool_use_id", "s1"), block("weird_internal"),
                block("fallback"), block("thinking", "signature", "s2"), block("tool_use", "id", "t2"));
        List<String> kept = AnthropicWire.applyFallbackEchoRule(blocks).stream()
                .map(b -> b.has("id") ? b.get("id").asText() : b.get("type").asText()).toList();
        assertEquals(List.of("text", "s1", "web_search_tool_result", "fallback", "thinking", "t2"), kept);
    }

    @Test
    void openAiCompatibleStreamingToolCallsAndReasoningEcho() throws Exception {
        try (FakeSseServer srv = new FakeSseServer()) {
            srv.respond(List.of(
                    FakeSseServer.oaChunk("{\"role\":\"assistant\",\"reasoning_content\":\"我需要查一下\"}", null),
                    FakeSseServer.oaChunk("{\"reasoning_content\":\" key\"}", null),
                    FakeSseServer.oaChunk("{\"content\":\"稍等\"}", null),
                    FakeSseServer.oaChunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"\"}}]}", null),
                    FakeSseServer.oaChunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"key\\\":\"}}]}", null),
                    FakeSseServer.oaChunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\" \\\"x\\\"}\"}}]}", null),
                    FakeSseServer.oaChunk("{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"key\\\": \\\"y\\\"}\"}}]}", null),
                    FakeSseServer.oaChunk("{}", "tool_calls"),
                    FakeSseServer.oaUsage("{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120,\"prompt_cache_hit_tokens\":60}"),
                    FakeSseServer.OA_DONE));
            srv.respond(List.of(FakeSseServer.oaChunk("{\"content\":\"x 和 y 都查到了\"}", null),
                    FakeSseServer.oaChunk("{}", "stop"), FakeSseServer.OA_DONE));

            ModelGateway gw = new ModelGatewayFactory(Map.of("deepseek", Map.of("base_url", srv.url() + "/v1", "api_key", "k")))
                    .create("deepseek:deepseek-v4-pro");
            assertInstanceOf(OpenAiCompatibleModelGateway.class, gw);
            Conversation c = Conversation.start();
            RunResult r = runAgent(gw, c, "查 x 和 y", new ArrayList<>());
            assertTrue(r.ok(), String.valueOf(r.error()));
            assertEquals("x 和 y 都查到了", r.text());

            Message a = c.messages().get(1);
            assertEquals("我需要查一下 key", a.thinking());
            assertEquals("稍等", a.text());
            assertEquals(List.of("call_a", "call_b"), a.toolCalls().stream().map(ToolCall::id).toList());
            assertEquals(Map.of("key", "x"), a.toolCalls().get(0).arguments());
            assertEquals(60, r.usage().cacheReadTokens()); // DeepSeek prompt_cache_hit_tokens
            assertEquals(40, r.usage().inputTokens());     // 100 - 60

            assertEquals("/v1/chat/completions", srv.requests.get(0).path());
            assertEquals("Bearer k", srv.requests.get(0).headers().get("authorization"));
            ArrayNode msgs = (ArrayNode) srv.requests.get(1).body().path("messages");
            assertEquals("system", msgs.get(0).path("role").asText());
            JsonNode assistant = msgs.get(2);
            assertEquals("我需要查一下 key", assistant.path("reasoning_content").asText()); // DeepSeek 思考模式要求回传
            assertEquals("{\"key\": \"x\"}", assistant.path("tool_calls").get(0).path("function").path("arguments").asText());
            assertEquals("tool", msgs.get(3).path("role").asText());
            assertEquals("value-of-x", msgs.get(3).path("content").asText());
            assertEquals("call_b", msgs.get(4).path("tool_call_id").asText());
            JsonNode body = srv.requests.get(0).body();
            assertTrue(body.path("stream_options").path("include_usage").asBoolean());
            assertEquals("lookup", body.path("tools").get(0).path("function").path("name").asText());
        }
    }

    @Test
    void openAiInvalidToolJsonIsReportedBackToModel() throws Exception {
        try (FakeSseServer srv = new FakeSseServer()) {
            srv.respond(List.of(FakeSseServer.oaChunk(
                    "{\"tool_calls\":[{\"index\":0,\"id\":\"call_z\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"key\\\": \"}}]}", null),
                    FakeSseServer.oaChunk("{}", "tool_calls"), FakeSseServer.OA_DONE));
            srv.respond(List.of(FakeSseServer.oaChunk("{\"content\":\"ok\"}", "stop"), FakeSseServer.OA_DONE));
            ModelGateway gw = new ModelGatewayFactory(Map.of("x", Map.of("base_url", srv.url(), "api_key", "k"))).create("x:m");
            Conversation c = Conversation.start();
            assertTrue(runAgent(gw, c, "go", new ArrayList<>()).ok());
            JsonNode toolMsg = srv.requests.get(1).body().path("messages").get(3);
            assertEquals("tool", toolMsg.path("role").asText());
            assertTrue(toolMsg.path("content").asText().contains("invalid tool arguments"));
        }
    }

    @Test
    void crossProviderMessageConversion() {
        List<Message> history = List.of(
                Message.user(List.of(new TextBlock("look"), ImageBlock.base64("image/png", "AAAA"))),
                Message.assistant(List.of(ThinkingBlock.of("deepseek thoughts", OpenAiWire.PROVIDER), new TextBlock("ok"),
                        ToolCall.of("call_1", "lookup", Map.of("key", "x"))), null),
                Message.user(List.of(new TextBlock("also this"), ToolResult.text("call_1", "lookup", "v", true))));
        List<AnthropicWire.WireMessage> an = AnthropicWire.toWire(history);
        // 其他厂商的思考内容没有签名，不发给 Claude
        assertEquals(List.of("text", "tool_use"), an.get(1).content().stream().map(n -> n.path("type").asText()).toList());
        assertEquals("tool_result", an.get(2).content().get(0).path("type").asText());
        assertTrue(an.get(2).content().get(0).path("is_error").asBoolean());
        assertEquals("base64", an.get(0).content().get(1).path("source").path("type").asText());

        ArrayNode oa = OpenAiWire.toWire("sys", history, true);
        assertEquals("image_url", oa.get(1).path("content").get(1).path("type").asText());
        assertEquals("deepseek thoughts", oa.get(2).path("reasoning_content").asText());
        assertEquals("Error: v", oa.get(3).path("content").asText());
        assertEquals("also this", oa.get(4).path("content").asText());
    }
}
