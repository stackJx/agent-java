package com.agentcore.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.ProviderPayload;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.infrastructure.json.DomainCodec;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.llm.ModelGatewayFactory;
import com.agentcore.infrastructure.llm.anthropic.AnthropicWire;
import com.agentcore.infrastructure.llm.openai.OpenAiCompatibleModelGateway;
import com.agentcore.infrastructure.llm.openai.OpenAiResponsesModelGateway;
import com.agentcore.infrastructure.llm.openai.OpenAiResponsesWire;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** OpenAI Responses API：通过本地假服务端走真实的 HTTP + SSE 解析路径。 */
class OpenAiResponsesGatewayTest {

    static final String USAGE = "{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":60,\"cache_write_tokens\":10},"
            + "\"output_tokens\":20,\"output_tokens_details\":{\"reasoning_tokens\":12},\"total_tokens\":120}";

    static ObjectNode reasoning(String id, String summary, String encrypted) {
        ObjectNode n = Json.object().put("id", id).put("type", "reasoning").put("encrypted_content", encrypted);
        n.putArray("summary").addObject().put("type", "summary_text").put("text", summary);
        return n;
    }

    static ObjectNode message(String id, String phase, String text) {
        ObjectNode n = Json.object().put("id", id).put("type", "message").put("role", "assistant").put("status", "completed");
        if (phase != null) {
            n.put("phase", phase);
        }
        n.putArray("content").addObject().put("type", "output_text").put("text", text).putArray("annotations");
        return n;
    }

    static ObjectNode call(String id, String callId, String args) {
        return Json.object().put("id", id).put("type", "function_call").put("call_id", callId).put("name", "lookup")
                .put("arguments", args).put("status", "completed");
    }

    static ModelGateway gateway(FakeSseServer srv) {
        return new ModelGatewayFactory(Map.of("openai", Map.of("base_url", srv.url() + "/v1", "api_key", "k")))
                .create("openai:gpt-test-2026");
    }

    @Test
    void toolLoopReplaysOutputItemsWithEncryptedReasoning() throws Exception {
        try (FakeSseServer srv = new FakeSseServer()) {
            srv.respond(FakeSseServer.responsesStream(List.of(
                    reasoning("rs_1", "先查一下 x 和 y", "ENC-1"),
                    message("msg_1", "commentary", "稍等，我查一下"),
                    call("fc_1", "call_a", "{\"key\": \"x\"}"),
                    call("fc_2", "call_b", "{\"key\":\"y\"}")), "completed", null, USAGE));
            srv.respond(FakeSseServer.responsesStream(List.of(message("msg_2", "final_answer", "x 和 y 都查到了")),
                    "completed", null, "{\"input_tokens\":200,\"output_tokens\":8}"));

            ModelGateway gw = gateway(srv);
            assertInstanceOf(OpenAiResponsesModelGateway.class, gw); // openai: 默认走 Responses
            Conversation c = Conversation.start();
            List<AgentEvent> events = new ArrayList<>();
            RunResult r = ModelGatewayTest.runAgent(gw, c, "查 x 和 y", events);
            assertTrue(r.ok(), String.valueOf(r.error()));
            assertEquals("x 和 y 都查到了", r.text());

            // 流式回调
            String streamedText = events.stream().filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).text()).reduce("", String::concat);
            assertTrue(streamedText.startsWith("稍等，我查一下"), streamedText);
            String streamedThinking = events.stream().filter(e -> e instanceof AgentEvent.ThinkingDelta)
                    .map(e -> ((AgentEvent.ThinkingDelta) e).text()).reduce("", String::concat);
            assertEquals("先查一下 x 和 y", streamedThinking);
            assertEquals(List.of("call_a", "call_b"), events.stream().filter(e -> e instanceof AgentEvent.ToolCallStreaming)
                    .map(e -> ((AgentEvent.ToolCallStreaming) e).id()).toList());

            // 领域消息
            Message a = c.messages().get(1);
            assertEquals("先查一下 x 和 y", a.thinking());
            assertEquals("稍等，我查一下", a.text());
            assertEquals(List.of("call_a", "call_b"), a.toolCalls().stream().map(ToolCall::id).toList());
            assertEquals(Map.of("key", "x"), a.toolCalls().get(0).arguments());
            assertEquals(OpenAiResponsesWire.PROVIDER, a.providerPayload().provider());

            // 用量：input_tokens 含缓存命中与写入
            ModelResponse first = null;
            for (AgentEvent e : events) {
                if (e instanceof AgentEvent.AssistantMessageCompleted m && first == null) {
                    assertEquals(30, m.usage().inputTokens());
                    assertEquals(60, m.usage().cacheReadTokens());
                    assertEquals(10, m.usage().cacheWriteTokens());
                    assertEquals(20, m.usage().outputTokens());
                    assertEquals(StopReason.TOOL_USE, m.stopReason());
                    first = new ModelResponse(m.message(), m.stopReason(), m.usage(), null, null, null);
                }
            }
            assertNotNull(first);

            // 第一次请求
            FakeSseServer.Received req0 = srv.requests.get(0);
            assertEquals("/v1/responses", req0.path());
            assertEquals("Bearer k", req0.headers().get("authorization"));
            JsonNode body0 = req0.body();
            assertEquals("gpt-test-2026", body0.path("model").asText());
            assertTrue(body0.path("instructions").asText().contains("you are a test agent"));
            assertTrue(body0.path("stream").asBoolean());
            assertFalse(body0.path("store").asBoolean(true));
            assertEquals("reasoning.encrypted_content", body0.path("include").get(0).asText());
            assertEquals(32_000, body0.path("max_output_tokens").asInt());
            JsonNode tool = body0.path("tools").get(0);
            assertEquals("function", tool.path("type").asText());
            assertEquals("lookup", tool.path("name").asText()); // 扁平结构，不是 {function: {...}}
            assertEquals("object", tool.path("parameters").path("type").asText());
            JsonNode in0 = body0.path("input");
            assertEquals(1, in0.size());
            assertEquals("user", in0.get(0).path("role").asText());
            assertEquals("input_text", in0.get(0).path("content").get(0).path("type").asText());
            assertEquals("查 x 和 y", in0.get(0).path("content").get(0).path("text").asText());

            // 第二次请求：原样回放上一轮 output（含加密推理、phase），再接两个工具结果
            JsonNode in1 = srv.requests.get(1).body().path("input");
            assertEquals(List.of("message", "reasoning", "message", "function_call", "function_call",
                    "function_call_output", "function_call_output"), types(in1));
            assertEquals("ENC-1", in1.get(1).path("encrypted_content").asText());
            assertEquals("commentary", in1.get(2).path("phase").asText());
            assertEquals("{\"key\": \"x\"}", in1.get(3).path("arguments").asText());
            assertEquals("call_a", in1.get(5).path("call_id").asText());
            assertEquals("value-of-x", in1.get(5).path("output").asText());
            assertEquals("call_b", in1.get(6).path("call_id").asText());
            assertEquals("value-of-y", in1.get(6).path("output").asText());
        }
    }

    @Test
    void stopReasonsForIncompleteAndRefusal() throws Exception {
        try (FakeSseServer srv = new FakeSseServer()) {
            srv.respond(FakeSseServer.responsesStream(List.of(message("m1", null, "写到一半")), "incomplete",
                    "max_output_tokens", "{\"input_tokens\":5,\"output_tokens\":5}"));
            ObjectNode refusal = Json.object().put("id", "m2").put("type", "message").put("role", "assistant");
            refusal.putArray("content").addObject().put("type", "refusal").put("refusal", "这个我不能帮忙");
            srv.respond(FakeSseServer.responsesStream(List.of(refusal), "completed", null, "{\"input_tokens\":5,\"output_tokens\":5}"));
            srv.respond(FakeSseServer.responsesStream(List.of(), "incomplete", "content_filter", "{\"input_tokens\":5,\"output_tokens\":0}"));

            ModelGateway gw = gateway(srv);
            ModelRequest req = ModelRequest.of("", List.of(Message.user("hi")), List.of());
            ModelResponse r1 = gw.complete(req);
            assertEquals(StopReason.MAX_TOKENS, r1.stopReason());
            assertEquals("写到一半", r1.message().text());
            assertEquals("incomplete:max_output_tokens", r1.rawStopReason());
            assertFalse(srv.requests.get(0).body().has("instructions")); // 空系统提示词不发

            ModelResponse r2 = gw.complete(req);
            assertEquals(StopReason.REFUSAL, r2.stopReason());
            assertEquals("这个我不能帮忙", r2.message().text());
            assertTrue(r2.stopDetails().contains("这个我不能帮忙"));

            assertEquals(StopReason.REFUSAL, gw.complete(req).stopReason());
        }
    }

    @Test
    void streamErrorsBecomeModelExceptions() throws Exception {
        try (FakeSseServer srv = new FakeSseServer()) {
            ObjectNode failed = Json.object();
            failed.putObject("response").put("id", "resp_x").put("status", "failed")
                    .putObject("error").put("code", "server_error").put("message", "boom");
            srv.respond(List.of(FakeSseServer.rs("response.failed", failed)));
            srv.respond(List.of(FakeSseServer.rs("error", Json.object().put("code", "context_length_exceeded")
                    .put("message", "Your input exceeds the context window of this model."))));
            srv.respond(List.of(FakeSseServer.rs("error", Json.object().put("code", "invalid_prompt").put("message", "bad"))));
            srv.respond(List.of(FakeSseServer.rs("response.created", Json.object()))); // 没有 completed 就断了

            ModelGateway gw = gateway(srv);
            ModelRequest req = ModelRequest.of("", List.of(Message.user("hi")), List.of());
            ModelException e1 = assertThrows(ModelException.class, () -> gw.complete(req));
            assertTrue(e1.retryable());
            assertTrue(e1.getMessage().contains("boom"));
            assertInstanceOf(ContextOverflowException.class, assertThrows(ModelException.class, () -> gw.complete(req)));
            assertFalse(assertThrows(ModelException.class, () -> gw.complete(req)).retryable());
            assertTrue(assertThrows(ModelException.class, () -> gw.complete(req)).retryable());
        }
    }

    @Test
    void requestOptionsAndReasoningSettings() throws Exception {
        try (FakeSseServer srv = new FakeSseServer()) {
            srv.respond(FakeSseServer.responsesStream(List.of(message("m", null, "ok")), "completed", null, "{}"));
            srv.respond(FakeSseServer.responsesStream(List.of(message("m", null, "ok")), "completed", null, "{}"));
            ModelGateway gw = new ModelGatewayFactory(Map.of("myproxy", Map.of("type", "openai-responses",
                    "base_url", srv.url(), "api_key", "k", "reasoning_effort", "high", "reasoning_summary", "auto",
                    "store", true, "max_tokens", 1000))).create("myproxy:some-model");
            assertInstanceOf(OpenAiResponsesModelGateway.class, gw);
            gw.complete(ModelRequest.of("sys", List.of(Message.user("hi")), List.of()));
            JsonNode b = srv.requests.get(0).body();
            assertEquals("/responses", srv.requests.get(0).path());
            assertEquals("high", b.path("reasoning").path("effort").asText());
            assertEquals("auto", b.path("reasoning").path("summary").asText());
            assertTrue(b.path("store").asBoolean());
            assertFalse(b.has("include")); // 服务端保存时不需要加密推理
            assertEquals(1000, b.path("max_output_tokens").asInt());

            // 单次请求的 effort 覆盖配置
            gw.complete(new ModelRequest("sys", List.of(Message.user("hi")), List.of(), null, Map.of("effort", "low")));
            assertEquals("low", srv.requests.get(1).body().path("reasoning").path("effort").asText());
        }
    }

    @Test
    void historyFromOtherProvidersIsConverted() {
        List<Message> history = List.of(
                Message.user(List.of(new TextBlock("look"), ImageBlock.base64("image/png", "AAAA"))),
                Message.assistant(List.of(new TextBlock("ok"), ToolCall.of("toolu_1", "lookup", Map.of("key", "x"))),
                        new ProviderPayload(AnthropicWire.PROVIDER, "claude", "[{\"type\":\"text\",\"text\":\"ok\"}]")),
                Message.user(List.of(new ToolResult("toolu_1", "lookup", List.of(new TextBlock("v"), ImageBlock.url("https://x/y.png")), true),
                        new TextBlock("also this"))));
        ArrayNode in = OpenAiResponsesWire.toInput(history);
        assertEquals(List.of("message", "message", "function_call", "function_call_output", "message"), types(in));
        assertEquals("input_image", in.get(0).path("content").get(1).path("type").asText());
        assertEquals("data:image/png;base64,AAAA", in.get(0).path("content").get(1).path("image_url").asText());
        assertEquals("assistant", in.get(1).path("role").asText()); // Claude 的原始块不会发给 OpenAI
        assertEquals("ok", in.get(1).path("content").asText());
        assertEquals("toolu_1", in.get(2).path("call_id").asText());
        assertEquals("{\"key\":\"x\"}", in.get(2).path("arguments").asText());
        JsonNode out = in.get(3).path("output");
        assertEquals("Error: v", out.get(0).path("text").asText()); // 带图片的工具结果用数组
        assertEquals("https://x/y.png", out.get(1).path("image_url").asText());
        assertEquals("also this", in.get(4).path("content").get(0).path("text").asText());
    }

    @Test
    void payloadSurvivesPersistence() {
        ArrayNode raw = Json.array().add(reasoning("rs_1", "s", "ENC")).add(message("m", "final_answer", "hi"));
        Message m = Message.assistant(List.of(new TextBlock("hi")), new ProviderPayload(OpenAiResponsesWire.PROVIDER, "gpt", Json.write(raw)));
        Message back = DomainCodec.message(Json.read(Json.write(DomainCodec.message(m))));
        JsonNode replay = OpenAiResponsesWire.toInput(List.of(back));
        assertEquals("ENC", replay.get(0).path("encrypted_content").asText());
        assertEquals("final_answer", replay.get(1).path("phase").asText());
    }

    @Test
    void providerTypeSelection() {
        ModelGatewayFactory f = new ModelGatewayFactory(Map.of(
                "openai_chat", Map.of("type", "openai-chat", "base_url", "http://localhost:1", "api_key", "k"),
                "bad", Map.of("type", "whatever", "api_key", "k")), k -> null);
        assertInstanceOf(OpenAiCompatibleModelGateway.class, f.create("deepseek:deepseek-v4-pro")); // 兼容厂商不变
        assertInstanceOf(OpenAiCompatibleModelGateway.class, f.create("openai_chat:gpt-4.1"));
        assertInstanceOf(OpenAiResponsesModelGateway.class, f.create("openai:gpt-6-astra"));
        assertThrows(IllegalArgumentException.class, () -> f.create("bad:m"));
    }

    static List<String> types(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(n -> out.add(n.path("type").asText()));
        return out;
    }
}
