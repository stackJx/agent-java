package com.agentcore.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.model.AgentDefinition;
import com.agentcore.domain.agent.model.EngineSettings;
import com.agentcore.domain.agent.model.RunControl;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.agent.service.AgentEngine;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.model.ToolRegistry;
import com.agentcore.infrastructure.llm.ModelGatewayFactory;
import com.agentcore.infrastructure.persistence.InMemoryConversationRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 对<b>真实的</b>模型 API 跑一遍：流式回复、工具调用往返、跨运行的历史回放。默认跳过（CI 里也不跑）。
 *
 * <p>用环境变量 {@code AGENTCORE_SMOKE_MODELS} 指定要测的模型（逗号分隔），API key 照常从环境变量读
 * （ANTHROPIC_API_KEY、OPENAI_API_KEY、DEEPSEEK_API_KEY ……）。每个模型几次很短的调用，费用很少：
 * <pre>
 * AGENTCORE_SMOKE_MODELS=anthropic:claude-haiku-5-5,deepseek:deepseek-v4-pro \
 *   mvn -pl agentcore-infrastructure test -Dtest=RealModelSmokeTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "AGENTCORE_SMOKE_MODELS", matches = ".*\\S.*")
class RealModelSmokeTest {

    static final String SECRET_NUMBER = "4172";

    static Stream<String> models() {
        return Arrays.stream(System.getenv("AGENTCORE_SMOKE_MODELS").split(",")).map(String::strip).filter(spec -> !spec.isEmpty());
    }

    static AgentEngine engine(String modelSpec, Tool... tools) {
        ModelGateway model = new ModelGatewayFactory(Map.of()).create(modelSpec);
        return AgentEngine.builder()
                .definition(new AgentDefinition("smoke", "You are a terse test assistant. Follow instructions exactly.",
                        EngineSettings.defaults(Path.of("."))))
                .model(model)
                .tools(new ToolRegistry(List.of(tools)))
                .repository(new InMemoryConversationRepository())
                .build();
    }

    static RunResult run(AgentEngine engine, Conversation conversation, String input, List<AgentEvent> events) {
        return engine.run(conversation, List.of(new TextBlock(input)), events::add, new RunControl());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    void streamsAPlainAnswer(String model) {
        List<AgentEvent> events = new ArrayList<>();
        RunResult result = run(engine(model), Conversation.start(), "Reply with the single word PONG.", events);
        assertTrue(result.ok(), String.valueOf(result.error()));
        assertTrue(result.text().toUpperCase().contains("PONG"), result.text());
        assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.TextDelta), "回复是流式到达的");
        assertTrue(result.usage().outputTokens() > 0, "厂商返回了用量");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    void callsAToolAndUsesItsResult(String model) {
        AtomicInteger calls = new AtomicInteger();
        Tool secret = FunctionTool.builder("get_secret_number").description("Returns the secret number.").readOnly(true)
                .handler((args, context) -> {
                    calls.incrementAndGet();
                    return ToolOutput.text(SECRET_NUMBER);
                }).build();
        RunResult result = run(engine(model, secret), Conversation.start(),
                "Call the get_secret_number tool, then reply with the number only.", new ArrayList<>());
        assertTrue(result.ok(), String.valueOf(result.error()));
        assertTrue(calls.get() >= 1, "模型调用了工具");
        assertTrue(result.text().contains(SECRET_NUMBER), result.text());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    void continuesTheConversationInALaterRun(String model) {
        AgentEngine engine = engine(model);
        Conversation conversation = Conversation.start();
        assertTrue(run(engine, conversation, "Remember the word PAPAYA. Reply OK.", new ArrayList<>()).ok());
        RunResult second = run(engine, conversation, "Which word did I ask you to remember? Reply with the word only.", new ArrayList<>());
        assertTrue(second.ok(), String.valueOf(second.error()));
        assertTrue(second.text().toUpperCase().contains("PAPAYA"), "历史（含思考块等厂商原始内容）回放正确：" + second.text());
    }
}
