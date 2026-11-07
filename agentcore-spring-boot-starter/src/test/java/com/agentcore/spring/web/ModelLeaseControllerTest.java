package com.agentcore.spring.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentcore.application.AgentService;
import com.agentcore.application.approval.AsyncApprover;
import com.agentcore.domain.agent.model.RunControl;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.interfaces.web.AgentController;
import com.agentcore.interfaces.web.ModelCatalog;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ModelLeaseControllerTest {
    @Test void closesOwnedGatewayOnlyAfterResultAndExactlyOnce() {
        AgentService service = mock(AgentService.class);
        ModelCatalog catalog = mock(ModelCatalog.class);
        ModelGateway gateway = mock(ModelGateway.class);
        var selection = new ModelCatalog.Selection("selected", gateway, true);
        var conversation = Conversation.start(); String id = conversation.id().value();
        when(service.find(id)).thenReturn(Optional.of(conversation));
        when(catalog.select("selected")).thenReturn(selection);
        CompletableFuture<RunResult> result = new CompletableFuture<>();
        when(service.chatAsync(eq(id), anyList(), any(), any(Runnable.class), same(gateway), eq("selected")))
                .thenReturn(new AgentService.RunHandle(id, new RunControl(), result));
        new AgentController(service, null, Duration.ZERO, catalog).send(id,
                "{\"text\":\"hello\",\"model_id\":\"selected\"}", new MockHttpServletRequest());
        verify(gateway, never()).close();
        result.complete(new RunResult(id, "ok", StopReason.END_TURN, 1, TokenUsage.ZERO, null));
        verify(gateway).close();
        selection.close(); verify(gateway, times(1)).close();
    }
    @Test void synchronousClaimConflictClosesSelectionWithoutAttachingOrDetachingApprovals() {
        AgentService service = mock(AgentService.class);
        AsyncApprover approver = mock(AsyncApprover.class);
        ModelCatalog catalog = mock(ModelCatalog.class);
        ModelGateway gateway = mock(ModelGateway.class);
        var conversation = Conversation.start(); String id = conversation.id().value();
        when(service.find(id)).thenReturn(Optional.of(conversation));
        when(catalog.select(null)).thenReturn(new ModelCatalog.Selection("selected", gateway, true));
        when(service.chatAsync(eq(id), anyList(), any(), any(Runnable.class), same(gateway), eq("selected")))
                .thenThrow(new IllegalStateException("already running"));
        AgentController controller = new AgentController(service, approver, Duration.ZERO, catalog);
        assertThatThrownBy(() -> controller.send(id, "{\"text\":\"hello\"}", new MockHttpServletRequest()))
                .hasMessage("already running");
        verify(gateway).close(); verifyNoInteractions(approver);
    }
    @Test void failureAfterClaimDetachesOnlyItsOwnApprovalChannel() {
        AgentService service = mock(AgentService.class);
        AsyncApprover approver = mock(AsyncApprover.class);
        ModelCatalog catalog = mock(ModelCatalog.class);
        ModelGateway gateway = mock(ModelGateway.class);
        var conversation = Conversation.start(); String id = conversation.id().value();
        when(service.find(id)).thenReturn(Optional.of(conversation));
        when(catalog.select(null)).thenReturn(new ModelCatalog.Selection("selected", gateway, true));
        when(service.chatAsync(eq(id), anyList(), any(), any(Runnable.class), same(gateway), eq("selected")))
                .thenAnswer(invocation -> {
                    invocation.getArgument(3, Runnable.class).run();
                    throw new IllegalStateException("metadata persistence failed");
                });
        assertThatThrownBy(() -> new AgentController(service, approver, Duration.ZERO, catalog)
                .send(id, "{\"text\":\"hello\"}", new MockHttpServletRequest()))
                .hasMessage("metadata persistence failed");
        verify(gateway).close();
        var channel = org.mockito.ArgumentCaptor.forClass(AsyncApprover.Channel.class);
        verify(approver).attach(eq(id), channel.capture());
        verify(approver).detach(id, channel.getValue());
    }

    @Test void exceptionalRunCompletionClosesGateway() {
        AgentService service = mock(AgentService.class);
        ModelCatalog catalog = mock(ModelCatalog.class);
        ModelGateway gateway = mock(ModelGateway.class);
        var conversation = Conversation.start(); String id = conversation.id().value();
        when(service.find(id)).thenReturn(Optional.of(conversation));
        when(catalog.select(null)).thenReturn(new ModelCatalog.Selection("selected", gateway, true));
        CompletableFuture<RunResult> result = new CompletableFuture<>();
        when(service.chatAsync(eq(id), anyList(), any(), any(Runnable.class), same(gateway), eq("selected")))
                .thenReturn(new AgentService.RunHandle(id, new RunControl(), result));
        new AgentController(service, null, Duration.ZERO, catalog).send(id, "{\"text\":\"hello\"}", new MockHttpServletRequest());
        result.completeExceptionally(new IllegalStateException("failed"));
        verify(gateway).close();
    }
}
