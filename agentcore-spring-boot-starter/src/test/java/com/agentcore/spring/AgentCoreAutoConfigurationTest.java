package com.agentcore.spring;

import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.call;
import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.reply;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.application.AgentService;
import com.agentcore.domain.agent.hook.AgentHook;
import com.agentcore.domain.agent.hook.HookDecision;
import com.agentcore.domain.agent.interceptor.ToolInterceptor;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.agent.service.AgentEngine;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.domain.conversation.repository.ConversationRepository;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.permission.service.PermissionPolicy;
import com.agentcore.domain.permission.service.RulePermissionPolicy;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.model.ToolRegistry;
import com.agentcore.domain.tool.schema.Schemas;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.infrastructure.persistence.InMemoryConversationRepository;
import com.agentcore.infrastructure.tool.annotation.AgentTool;
import com.agentcore.infrastructure.tool.annotation.Param;
import com.agentcore.interfaces.web.AgentController;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AgentCoreAutoConfigurationTest {

    @TempDir
    Path dir;

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentCoreAutoConfiguration.class,
                        com.agentcore.spring.web.AgentCoreWebAutoConfiguration.class))
                .withPropertyValues(
                        "agentcore.model=mock",
                        "agentcore.agent.working-directory=" + dir,
                        "agentcore.sessions.dir=" + dir.resolve("sessions"),
                        "agentcore.memory.dir=" + dir.resolve("memory"),
                        "agentcore.skills.dirs=" + dir.resolve("skills"));
    }

    /** 业务工具：普通 Spring Bean 上的 @AgentTool 方法。 */
    public static class OrderTools {
        @AgentTool(description = "查询订单状态", readOnly = true)
        public Map<String, Object> getOrder(@Param("订单号") String orderId) {
            return Map.of("order_id", orderId, "status", "shipped", "amount", 199.0);
        }

        @AgentTool(description = "给订单退款")
        public String refund(@Param("订单号") String orderId, @Param("金额") double amount) {
            return "refunded " + amount;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserExtensions {
        @Bean
        OrderTools orderTools() {
            return new OrderTools();
        }

        @Bean
        Tool weatherTool() {
            return FunctionTool.builder("get_weather").description("天气").readOnly(true)
                    .schema(Schemas.object().string("city", "城市", true).build())
                    .handler((a, c) -> ToolOutput.text("晴")).build();
        }

        @Bean
        AgentHook refundLimit() {
            return new AgentHook() {
                @Override
                public HookDecision beforeTool(ToolCall call, Tool tool, Map<String, Object> args, ToolContext ctx) {
                    return call.name().equals("refund") && ((Number) args.get("amount")).doubleValue() > 100
                            ? HookDecision.block("超过 100 元需人工处理") : HookDecision.proceed();
                }
            };
        }
    }

    @Test
    void assemblesEverythingAndCollectsUserExtensions() {
        runner().withUserConfiguration(UserExtensions.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(AgentService.class).hasSingleBean(AgentEngine.class)
                    .doesNotHaveBean(AgentController.class); // 非 Web 应用不注册控制器
            List<String> names = ctx.getBean(ToolRegistry.class).names();
            assertThat(names).contains("read_file", "bash", "memory_save", "task", "get_order", "refund", "get_weather");
            assertThat(ctx.getBean(AgentEngine.class).hooks()).hasSize(1);
            RunResult r = ctx.getBean(AgentService.class).chat(
                    ctx.getBean(AgentService.class).startConversation(Map.of()).id().value(), "你好", e -> { });
            assertThat(r.ok()).isTrue();
            assertThat(r.text()).contains("(mock)");
        });
    }

    @Test
    void userModelAndToolsWorkEndToEnd() {
        ScriptedModelGateway model = new ScriptedModelGateway()
                .then(reply("先查订单", call("get_order", "orderId", "A1001"), call("refund", "orderId", "A1001", "amount", 199)))
                .then(reply("已转人工"));
        runner().withUserConfiguration(UserExtensions.class)
                .withBean(ModelGateway.class, () -> model)
                .withPropertyValues("agentcore.permissions.mode=auto")
                .run(ctx -> {
                    AgentService svc = ctx.getBean(AgentService.class);
                    String id = svc.startConversation(Map.of()).id().value();
                    assertThat(svc.chat(id, "退款", e -> { }).text()).isEqualTo("已转人工");
                    List<ToolResult> results = svc.find(id).orElseThrow().messages().get(2).toolResults();
                    assertThat(results.get(0).text()).contains("\"status\" : \"shipped\"");
                    assertThat(results.get(1).isError()).isTrue();
                    assertThat(results.get(1).text()).contains("超过 100 元需人工处理");
                });
    }

    @Test
    void propertiesAndOverrides() {
        runner().withPropertyValues("agentcore.subagent-max-depth=0", "agentcore.tools=read_file,grep",
                        "agentcore.memory.enabled=false", "agentcore.agent.instructions=用中文回答")
                .withBean(ConversationRepository.class, InMemoryConversationRepository::new)
                .run(ctx -> {
                    assertThat(ctx.getBean(ToolRegistry.class).names()).containsExactly("read_file", "grep");
                    assertThat(ctx.getBean(ConversationRepository.class)).isInstanceOf(InMemoryConversationRepository.class);
                    assertThat(ctx.getBean(AgentEngine.class).definition().systemPrompt()).contains("用中文回答");
                    assertThat(ctx.getBean(PermissionPolicy.class)).isInstanceOf(RulePermissionPolicy.class); // 默认：配置规则
                });
    }

    @Test
    void toolInterceptorBeansJoinTheChain() {
        ToolInterceptor audit = (inv, chain) -> chain.proceed(inv);
        runner().withBean(ToolInterceptor.class, () -> audit)
                .run(ctx -> assertThat(ctx.getBean(AgentEngine.class).interceptors()).containsExactly(audit));
    }

    @Test
    void customPermissionPolicyReplacesRules() {
        PermissionPolicy tenantPolicy = (tool, args) -> PermissionPolicy.Verdict.deny("tenant policy");
        runner().withBean(PermissionPolicy.class, () -> tenantPolicy)
                .run(ctx -> assertThat(ctx.getBean(AgentEngine.class).permissions()).isSameAs(tenantPolicy));
    }
}
