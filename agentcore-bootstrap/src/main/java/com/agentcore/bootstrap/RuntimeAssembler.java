package com.agentcore.bootstrap;

import com.agentcore.application.AgentRuntime;
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
import com.agentcore.domain.tool.Tool;
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
import com.agentcore.infrastructure.persistence.jdbc.JdbcConversationRepository;
import com.agentcore.infrastructure.persistence.jdbc.JdbcMemoryRepository;
import com.agentcore.infrastructure.persistence.jdbc.JdbcPlatform;
import com.agentcore.infrastructure.persistence.jdbc.JdbcSchema;
import com.agentcore.infrastructure.persistence.jdbc.JdbcStore;
import com.agentcore.infrastructure.persistence.jdbc.SimpleDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装配器：把配置（{@link Settings}）变成一个可用的 {@link AgentRuntime}。
 * 嵌入到自己的应用里时，可以直接调用它，也可以参照它手动组装各个组件。
 */
public final class RuntimeAssembler {

    /** 宿主提供的交互端口与扩展。 */
    public record Options(Path cwd, Approver approver, UserPrompter prompter, ModelGateway model, List<Tool> extraTools,
                          List<AgentHook> hooks) {
        public static Options of(Path cwd) {
            return new Options(cwd, null, null, null, List.of(), List.of());
        }

        public Options withApprover(Approver a) {
            return new Options(cwd, a, prompter, model, extraTools, hooks);
        }

        public Options withPrompter(UserPrompter p) {
            return new Options(cwd, approver, p, model, extraTools, hooks);
        }

        public Options withModel(ModelGateway m) {
            return new Options(cwd, approver, prompter, m, extraTools, hooks);
        }

        public Options withExtraTools(List<Tool> t) {
            return new Options(cwd, approver, prompter, model, t, hooks);
        }

        public Options withHooks(List<AgentHook> h) {
            return new Options(cwd, approver, prompter, model, extraTools, h);
        }
    }

    private RuntimeAssembler() {
    }

    public static AgentRuntime assemble(Settings s, Options o) {
        Path cwd = o.cwd().toAbsolutePath().normalize();
        List<AutoCloseable> resources = new ArrayList<>();
        Map<String, String> warnings = new LinkedHashMap<>();
        ModelGatewayFactory factory = new ModelGatewayFactory(s.mapOfMaps("providers"));

        ModelGateway model = o.model();
        if (model == null) {
            String spec = s.string("model", null);
            if (spec == null) {
                throw new IllegalArgumentException("没有配置模型。请设置环境变量（ANTHROPIC_API_KEY / DEEPSEEK_API_KEY / OPENAI_API_KEY）"
                        + "或 AGENTCORE_MODEL，或在 agentcore.yaml 里写 model: deepseek:deepseek-v4-pro");
            }
            model = factory.create(spec);
        }
        resources.add(model);

        // ---- 工具
        ToolRegistry tools = new ToolRegistry();
        List<String> names = s.strings("tools");
        tools.registerAll(BuiltinTools.select(names));
        SystemPromptBuilder prompt = new SystemPromptBuilder()
                .name(s.string("agent.name", "agentcore"))
                .basePrompt(systemPrompt(s, cwd))
                .cwd(cwd)
                .instructions(s.string("agent.instructions", ""))
                .projectInstructions(s.bool("project_instructions", true));

        JdbcStore jdbc = jdbcStore(s);
        MemoryRepository memory = null;
        if (s.bool("memory.enabled", true)) {
            memory = jdbc != null ? new JdbcMemoryRepository(jdbc, s.string("persistence.jdbc.memory_scope", "default"))
                    : new FileMemoryRepository(resolve(cwd, s.string("memory.dir", Settings.home(System::getenv) + "/memory")));
            tools.registerAll(MemoryTools.create(memory));
            prompt.memory(memory);
        }
        if (s.bool("skills.enabled", true)) {
            List<String> dirs = s.strings("skills.dirs");
            SkillCatalog skills = new FileSkillCatalog((dirs == null ? List.<String>of() : dirs).stream().map(d -> resolve(cwd, d)).toList());
            if (!skills.list().isEmpty()) {
                tools.register(SkillTool.create(skills));
                prompt.skills(skills);
            }
        }
        Map<String, Map<String, Object>> mcpCfg = s.mapOfMaps("mcp_servers");
        if (!mcpCfg.isEmpty()) {
            McpServerManager mcp = new McpServerManager(mcpCfg, cwd);
            tools.registerAll(mcp.start());
            mcp.errors().forEach((k, v) -> warnings.put("MCP 服务器 " + k + " 启动失败", v));
            prompt.section(mcp.promptSection());
            resources.add(mcp);
        }
        if (o.extraTools() != null) {
            tools.registerAll(o.extraTools());
        }
        int maxDepth = s.integer("subagent_max_depth", 1);
        TaskTool task = null;
        if (maxDepth > 0 && (names == null || names.contains("task"))) {
            Map<String, SubAgentProfile> profiles = new LinkedHashMap<>();
            s.mapOfMaps("subagents").forEach((n, d) -> profiles.put(n, new SubAgentProfile(n, str(d.get("description")),
                    (String) d.get("system_prompt"), str(d.get("instructions")),
                    d.get("tools") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : null,
                    (String) d.get("model"), d.get("max_turns") instanceof Number num ? num.intValue() : 50)));
            task = new TaskTool(profiles, factory);
            tools.register(task);
        }

        // ---- 会话仓储
        ConversationRepository repo = jdbc != null ? new JdbcConversationRepository(jdbc)
                : s.bool("sessions.enabled", true)
                ? new JsonlConversationRepository(resolve(cwd, s.string("sessions.dir", Settings.home(System::getenv) + "/sessions")))
                : new InMemoryConversationRepository();

        // ---- 引擎
        EngineSettings es = new EngineSettings(cwd, s.integer("agent.max_turns", 100), 8, 3, Duration.ofSeconds(2), 2, maxDepth);
        CompactionSettings cs = new CompactionSettings(s.decimal("context.threshold", 0.75), s.integer("context.max_tokens", 0),
                8000, 120_000);
        AgentEngine.Builder eb = AgentEngine.builder()
                .definition(new AgentDefinition(s.string("agent.name", "agentcore"), prompt.build(), es))
                .model(model)
                .tools(tools)
                .permissions(PermissionPolicy.of(s.string("permissions.mode", "default"), s.strings("permissions.allow"),
                        s.strings("permissions.deny"), s.strings("permissions.ask")))
                .approver(o.approver())
                .repository(repo)
                .compactor(new ConversationCompactor(cs))
                .hooks(o.hooks() == null ? List.of() : o.hooks());
        String summarizer = s.string("context.summarizer_model", null);
        if (summarizer != null) {
            ModelGateway sm = factory.create(summarizer);
            resources.add(sm);
            eb.summarizer(sm);
        }
        if (o.prompter() != null) {
            eb.service(UserPrompter.class, o.prompter());
        }
        AgentEngine engine = eb.build();
        if (task != null) {
            task.attach(engine);
        }
        return new AgentRuntime(new AgentService(engine, repo), engine, memory, warnings, resources);
    }

    /** 配置了 persistence.jdbc.url 时，会话与长期记忆改存数据库（JDBC 驱动需要在 classpath 上）。 */
    static JdbcStore jdbcStore(Settings s) {
        String url = s.string("persistence.jdbc.url", null);
        if (url == null) {
            return null;
        }
        JdbcStore store = new JdbcStore(new SimpleDataSource(url, s.string("persistence.jdbc.username", null),
                s.string("persistence.jdbc.password", null)), JdbcPlatform.parse(s.string("persistence.jdbc.platform", "auto")),
                s.string("persistence.jdbc.table_prefix", "agent_"));
        if (!"never".equalsIgnoreCase(s.string("persistence.jdbc.initialize_schema", "create-if-missing"))) {
            JdbcSchema.createIfMissing(store);
        }
        return store;
    }

    private static String systemPrompt(Settings s, Path cwd) {
        String file = s.string("agent.system_prompt_file", null);
        if (file != null) {
            try {
                return Files.readString(resolve(cwd, file));
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read system_prompt_file: " + file);
            }
        }
        return s.string("agent.system_prompt", null);
    }

    static Path resolve(Path cwd, String p) {
        String expanded = p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p;
        Path path = Path.of(expanded);
        return path.isAbsolute() ? path : cwd.resolve(path);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
