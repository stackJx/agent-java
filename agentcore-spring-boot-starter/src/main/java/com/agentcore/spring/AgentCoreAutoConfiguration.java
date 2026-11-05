package com.agentcore.spring;

import com.agentcore.application.AgentService;
import com.agentcore.application.prompt.SystemPromptBuilder;
import com.agentcore.application.subagent.SubAgentProfile;
import com.agentcore.application.subagent.TaskTool;
import com.agentcore.application.tool.MemoryTools;
import com.agentcore.application.tool.SkillTool;
import com.agentcore.domain.agent.AgentDefinition;
import com.agentcore.domain.agent.AgentEngine;
import com.agentcore.domain.agent.AgentHook;
import com.agentcore.domain.agent.EngineSettings;
import com.agentcore.domain.context.CompactionSettings;
import com.agentcore.domain.context.ConversationCompactor;
import com.agentcore.domain.conversation.ConversationRepository;
import com.agentcore.domain.memory.MemoryRepository;
import com.agentcore.domain.model.ModelGateway;
import com.agentcore.domain.permission.Approver;
import com.agentcore.domain.permission.PermissionPolicy;
import com.agentcore.domain.skill.SkillCatalog;
import com.agentcore.domain.tool.ToolRegistry;
import com.agentcore.domain.tool.UserPrompter;
import com.agentcore.infrastructure.config.Settings;
import com.agentcore.infrastructure.llm.ModelGatewayFactory;
import com.agentcore.infrastructure.mcp.McpServerManager;
import com.agentcore.infrastructure.persistence.FileMemoryRepository;
import com.agentcore.infrastructure.persistence.FileSkillCatalog;
import com.agentcore.infrastructure.persistence.InMemoryConversationRepository;
import com.agentcore.infrastructure.persistence.JsonlConversationRepository;
import com.agentcore.infrastructure.tool.builtin.BuiltinTools;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * agentcore 自动装配。所有 Bean 都可以被应用自己定义的同类型 Bean 替换（{@code @ConditionalOnMissingBean}），
 * 例如自定义 {@link ConversationRepository}（数据库存储）、{@link Approver}（审批流）、{@link ModelGateway}。
 *
 * <p>应用里的扩展会被自动收集：
 * <ul>
 *   <li>带 {@code @AgentTool} 方法的 Bean、以及 {@code Tool} 类型的 Bean → 注册为工具；</li>
 *   <li>{@link AgentHook} Bean → 引擎钩子（按 {@code @Order} 排序）；</li>
 *   <li>{@link Approver} / {@link UserPrompter} Bean → 审批与提问端口。</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentCoreProperties.class)
public class AgentCoreAutoConfiguration {

    /** 用于上下文压缩的独立模型（可选）。 */
    public record AgentSummarizer(ModelGateway gateway) implements AutoCloseable {
        @Override
        public void close() {
            gateway.close();
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelGatewayFactory agentModelGatewayFactory(AgentCoreProperties props) {
        return new ModelGatewayFactory(props.providerConfigs());
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelGateway agentModelGateway(AgentCoreProperties props, ModelGatewayFactory factory) {
        String spec = props.getModel() != null ? props.getModel() : Settings.defaultModel(System::getenv);
        if (spec == null) {
            throw new IllegalStateException("agentcore: 没有配置模型。请设置 agentcore.model（例如 deepseek:deepseek-v4-pro），"
                    + "或设置 ANTHROPIC_API_KEY / DEEPSEEK_API_KEY / OPENAI_API_KEY 环境变量，或自定义 ModelGateway Bean。");
        }
        return factory.create(spec);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agentcore.context", name = "summarizer-model")
    @ConditionalOnMissingBean
    public AgentSummarizer agentSummarizer(AgentCoreProperties props, ModelGatewayFactory factory) {
        return new AgentSummarizer(factory.create(props.getContext().getSummarizerModel()));
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationRepository agentConversationRepository(AgentCoreProperties props) {
        return props.getSessions().isEnabled()
                ? new JsonlConversationRepository(resolve(props, props.getSessions().getDir()))
                : new InMemoryConversationRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "agentcore.memory.enabled", matchIfMissing = true)
    public MemoryRepository agentMemoryRepository(AgentCoreProperties props) {
        return new FileMemoryRepository(resolve(props, props.getMemory().getDir()));
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillCatalog agentSkillCatalog(AgentCoreProperties props) {
        List<Path> dirs = props.getSkills().isEnabled()
                ? props.getSkills().getDirs().stream().map(d -> resolve(props, d)).toList() : List.of();
        return new FileSkillCatalog(dirs);
    }

    @Bean
    @ConditionalOnMissingBean
    public McpServerManager agentMcpServers(AgentCoreProperties props) {
        McpServerManager m = new McpServerManager(props.mcpConfigs(), props.workingDirectory());
        m.start();
        m.errors().forEach((k, v) -> System.getLogger(AgentCoreAutoConfiguration.class.getName())
                .log(System.Logger.Level.WARNING, "MCP server " + k + " failed to start: " + v));
        return m;
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionPolicy agentPermissionPolicy(AgentCoreProperties props) {
        AgentCoreProperties.Permissions p = props.getPermissions();
        return PermissionPolicy.of(p.getMode(), p.getAllow(), p.getDeny(), p.getAsk());
    }

    /** 子 Agent 工具（subagent-max-depth 为 0 或 tools 白名单不含 task 时不会注册进工具表）。 */
    @Bean
    @ConditionalOnMissingBean
    public TaskTool agentTaskTool(AgentCoreProperties props, ModelGatewayFactory factory) {
        Map<String, SubAgentProfile> profiles = new LinkedHashMap<>();
        props.getSubagents().forEach((name, s) -> profiles.put(name, new SubAgentProfile(name, s.getDescription(),
                s.getSystemPrompt(), s.getInstructions(), s.getTools(), s.getModel(), s.getMaxTurns())));
        return new TaskTool(profiles, factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry agentToolRegistry(AgentCoreProperties props, ObjectProvider<MemoryRepository> memory, SkillCatalog skills,
                                          McpServerManager mcp, ObjectProvider<TaskTool> taskTool) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(BuiltinTools.select(props.getTools()));
        memory.ifAvailable(m -> registry.registerAll(MemoryTools.create(m)));
        if (!skills.list().isEmpty()) {
            registry.register(SkillTool.create(skills));
        }
        registry.registerAll(mcp.tools());
        if (props.getSubagentMaxDepth() > 0 && (props.getTools() == null || props.getTools().contains("task"))) {
            taskTool.ifAvailable(registry::register);
        }
        return registry;
    }

    /** 启动完成后把应用里的 @AgentTool 方法和 Tool Bean 注册进工具表。 */
    @Bean
    public static AgentToolRegistrar agentToolRegistrar() {
        return new AgentToolRegistrar();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentEngine agentEngine(AgentCoreProperties props, ModelGateway model, ToolRegistry tools, PermissionPolicy permissions,
                                   ConversationRepository repository, ObjectProvider<MemoryRepository> memory, SkillCatalog skills,
                                   McpServerManager mcp, ObjectProvider<Approver> approver, ObjectProvider<UserPrompter> prompter,
                                   ObjectProvider<AgentHook> hooks, ObjectProvider<TaskTool> taskTool,
                                   ObjectProvider<AgentSummarizer> summarizer) {
        AgentCoreProperties.Agent a = props.getAgent();
        Path cwd = props.workingDirectory();
        SystemPromptBuilder prompt = new SystemPromptBuilder()
                .name(a.getName())
                .basePrompt(systemPrompt(props))
                .cwd(cwd)
                .instructions(a.getInstructions())
                .projectInstructions(a.isProjectInstructions())
                .skills(skills)
                .section(mcp.promptSection());
        memory.ifAvailable(prompt::memory);

        EngineSettings settings = new EngineSettings(cwd, a.getMaxTurns(), a.getMaxToolConcurrency(), 3, Duration.ofSeconds(2), 2,
                props.getSubagentMaxDepth());
        AgentCoreProperties.Context c = props.getContext();
        AgentEngine.Builder b = AgentEngine.builder()
                .definition(new AgentDefinition(a.getName(), prompt.build(), settings))
                .model(model)
                .tools(tools)
                .permissions(permissions)
                .approver(approver.getIfAvailable())
                .hooks(hooks.orderedStream().toList())
                .repository(repository)
                .compactor(new ConversationCompactor(new CompactionSettings(c.getThreshold(), c.getMaxTokens(), 8000, 120_000)));
        summarizer.ifAvailable(s -> b.summarizer(s.gateway()));
        prompter.ifAvailable(p -> b.service(UserPrompter.class, p));
        AgentEngine engine = b.build();
        taskTool.ifAvailable(t -> t.attach(engine));
        return engine;
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentService agentService(AgentEngine engine, ConversationRepository repository) {
        return new AgentService(engine, repository);
    }

    private static String systemPrompt(AgentCoreProperties props) {
        String file = props.getAgent().getSystemPromptFile();
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(resolve(props, file));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read agentcore.agent.system-prompt-file: " + file, e);
            }
        }
        return props.getAgent().getSystemPrompt();
    }

    static Path resolve(AgentCoreProperties props, String p) {
        String expanded = p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p;
        Path path = Path.of(expanded);
        return path.isAbsolute() ? path : props.workingDirectory().resolve(path);
    }
}
