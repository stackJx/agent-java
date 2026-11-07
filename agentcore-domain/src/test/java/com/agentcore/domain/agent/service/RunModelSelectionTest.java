package com.agentcore.domain.agent.service;

import static com.agentcore.domain.testsupport.FakeModel.call;
import static com.agentcore.domain.testsupport.FakeModel.reply;
import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.model.AgentDefinition;
import com.agentcore.domain.agent.model.EngineSettings;
import com.agentcore.domain.agent.model.RunControl;
import com.agentcore.domain.context.model.CompactionSettings;
import com.agentcore.domain.context.service.ConversationCompactor;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.testsupport.FakeModel;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.model.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RunModelSelectionTest {

    @Test
    void switchingModelPreservesToolStateAndReportsActualModel() {
        var counter = FunctionTool.builder("counter").readOnly(true)
                .handler((args, ctx) -> ToolOutput.text(String.valueOf(
                        ctx.state().merge("count", 1, (old, next) -> (Integer) old + 1))))
                .build();
        var original = new NamedModel("original", new FakeModel()
                .then(reply("", call("counter"))).then(reply("first")));
        var selected = new NamedModel("selected", new FakeModel()
                .then(reply("", call("counter"))).then(reply("second")));
        var engine = builder(original).tools(new ToolRegistry(List.of(counter))).build();
        var conversation = Conversation.start();
        engine.run(conversation, input("first"), null, new RunControl());
        var events = new ArrayList<AgentEvent>();
        assertEquals("second", engine.run(conversation, input("second"), events::add, new RunControl(), selected).text());
        assertEquals("2", conversation.messages().get(6).toolResults().getFirst().text());
        assertEquals("fixture:selected", ((AgentEvent.RunStarted) events.getFirst()).model());
        assertSame(original, engine.model());
        assertFalse(selected.closed, "the owner, not the engine, closes a selected gateway");
        engine.forgetToolState(conversation.id().value());
    }

    @Test
    void concurrentRunsDoNotMutateEachOthersModel() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var original = new NamedModel("original", new FakeModel().then(request -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("run was not released");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return reply("original reply");
        }));
        var selected = new NamedModel("selected", new FakeModel().then(reply("selected reply")));
        var engine = builder(original).build();
        var first = CompletableFuture.supplyAsync(() -> engine.run(Conversation.start(), input("a"), null, new RunControl()));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("selected reply", engine.run(Conversation.start(), input("b"), null, new RunControl(), selected).text());
            assertSame(original, engine.model());
        } finally {
            release.countDown();
        }
        assertEquals("original reply", first.get(5, TimeUnit.SECONDS).text());
    }

    @Test
    void automaticCompactionFollowsSelectedModelAndItsContextWindow() {
        var original = new NamedModel("original", new FakeModel());
        var selectedScript = new FakeModel(2000)
                .then(reply("", call("big")))
                .then(reply("", call("big")))
                .then(request -> {
                    assertEquals(ConversationCompactor.SUMMARY_SYSTEM, request.system());
                    return reply("SUMMARY: previous work");
                })
                .then(reply("continued"));
        var selected = new NamedModel("selected", selectedScript);
        var big = FunctionTool.builder("big").readOnly(true)
                .handler((args, ctx) -> ToolOutput.text("x".repeat(4000))).build();
        var engine = builder(original).tools(new ToolRegistry(List.of(big)))
                .compactor(new ConversationCompactor(new CompactionSettings(0.75, 0, 1000, 10_000))).build();
        var conversation = Conversation.start();
        assertEquals("continued", engine.run(conversation, input("go"), null, new RunControl(), selected).text());
        assertEquals(1, conversation.compactions());
        assertEquals(4, selectedScript.requests.size());
        assertTrue(original.delegate.requests.isEmpty());
    }

    @Test
    void explicitSummarizerIsNotReplacedByModelSelection() {
        var original = new NamedModel("original", new FakeModel());
        var summaryScript = new FakeModel().then(reply("SUMMARY: previous work"));
        var selected = new NamedModel("selected", new FakeModel(2000)
                .then(reply("", call("big"))).then(reply("", call("big"))).then(reply("continued")));
        var big = FunctionTool.builder("big").readOnly(true)
                .handler((args, ctx) -> ToolOutput.text("x".repeat(4000))).build();
        var engine = builder(original).summarizer(summaryScript).tools(new ToolRegistry(List.of(big)))
                .compactor(new ConversationCompactor(new CompactionSettings(0.75, 0, 1000, 10_000))).build();
        assertEquals("continued", engine.run(Conversation.start(), input("go"), null, new RunControl(), selected).text());
        assertEquals(1, summaryScript.requests.size());
        assertEquals(ConversationCompactor.SUMMARY_SYSTEM, summaryScript.requests.getFirst().system());
    }

    private static List<TextBlock> input(String text) {
        return List.of(new TextBlock(text));
    }

    private static AgentEngine.Builder builder(ModelGateway gateway) {
        return AgentEngine.builder().definition(new AgentDefinition("test", "system", EngineSettings.defaults(Path.of("."))))
                .model(gateway);
    }

    private static final class NamedModel implements ModelGateway {
        private final String name;
        private final FakeModel delegate;
        private boolean closed;

        private NamedModel(String name, FakeModel delegate) {
            this.name = name;
            this.delegate = delegate;
        }

        @Override public String provider() { return "fixture"; }
        @Override public String model() { return name; }
        @Override public int contextWindow() { return delegate.contextWindow(); }
        @Override public ModelResponse stream(ModelRequest request, ModelStreamListener listener, CancellationToken cancel) {
            return delegate.stream(request, listener, cancel);
        }
        @Override public void close() { closed = true; }
    }
}
