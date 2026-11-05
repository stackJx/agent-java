package com.agentcore.spring;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agentcore 配置（application.yml 中的 {@code agentcore.*}）。
 *
 * <pre>
 * agentcore:
 *   model: deepseek:deepseek-v4-pro
 *   providers:
 *     deepseek:
 *       api-key-env: DEEPSEEK_API_KEY
 *   permissions:
 *     allow: ["bash(git status*)"]
 * </pre>
 */
@ConfigurationProperties(prefix = "agentcore")
public class AgentCoreProperties {

    private static final String HOME = System.getenv().getOrDefault("AGENTCORE_HOME",
            System.getProperty("user.home") + "/.agentcore");

    /** 模型，格式 provider:model，例如 deepseek:deepseek-v4-pro、anthropic:claude-opus-5-5、mock。未设置时按 API Key 环境变量推断。 */
    private String model;

    /** 模型服务配置，key 为 provider 名（anthropic、deepseek、qwen …，或自定义名）。 */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    private final Agent agent = new Agent();

    /** 启用的内置工具（read_file、bash、task …）；不设置表示全部。 */
    private List<String> tools;

    private final Permissions permissions = new Permissions();

    /** MCP 服务器，key 为服务器名；工具名为 mcp__&lt;服务器名&gt;__&lt;工具名&gt;。 */
    private Map<String, McpServer> mcpServers = new LinkedHashMap<>();

    /** 子 Agent 角色，主 Agent 通过 task 工具派活。 */
    private Map<String, SubAgent> subagents = new LinkedHashMap<>();

    /** 子 Agent 最大嵌套深度；0 表示不启用 task 工具。 */
    private int subagentMaxDepth = 1;

    private final Memory memory = new Memory();
    private final Skills skills = new Skills();
    private final Sessions sessions = new Sessions();
    private final Context context = new Context();
    private final Web web = new Web();
    private final Persistence persistence = new Persistence();

    // ------------------------------------------------------------------ 嵌套类型

    public static class Persistence {
        private final Jdbc jdbc = new Jdbc();

        public Jdbc getJdbc() { return jdbc; }
    }

    /** 数据库存储（容器里需要有唯一的 DataSource）。 */
    public static class Jdbc {
        /** 是否把会话与长期记忆存入应用的数据源（默认 false：存文件）。 */
        private boolean enabled;
        /** 数据库平台：auto（按 JDBC 元数据识别）/ h2 / mysql / postgresql / oracle / sqlserver。 */
        private String platform = "auto";
        /** 表名前缀。 */
        private String tablePrefix = "agent_";
        /** 启动时是否自动创建缺失的表：create-if-missing / never（由 DBA 执行建表脚本）。 */
        private String initializeSchema = "create-if-missing";
        /** 长期记忆的隔离空间（例如按应用或租户区分）。 */
        private String memoryScope = "default";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPlatform() { return platform; }
        public void setPlatform(String platform) { this.platform = platform; }
        public String getTablePrefix() { return tablePrefix; }
        public void setTablePrefix(String tablePrefix) { this.tablePrefix = tablePrefix; }
        public String getInitializeSchema() { return initializeSchema; }
        public void setInitializeSchema(String initializeSchema) { this.initializeSchema = initializeSchema; }
        public String getMemoryScope() { return memoryScope; }
        public void setMemoryScope(String memoryScope) { this.memoryScope = memoryScope; }
    }

    public static class Provider {
        /** 协议类型：anthropic、openai（Chat Completions 兼容）或 openai-responses（Responses API）。默认按 provider 名推断。 */
        private String type;
        private String baseUrl;
        private String apiKey;
        /** 从哪个环境变量读取 API Key。 */
        private String apiKeyEnv;
        private Integer maxTokens;
        /** OpenAI 兼容：max_tokens 参数名（部分服务要求 max_completion_tokens）。 */
        private String maxTokensParam;
        private Integer contextWindow;
        /** OpenAI：reasoning_effort（Responses 下即 reasoning.effort）。 */
        private String reasoningEffort;
        /** OpenAI Responses：reasoning.summary（auto / concise / detailed），开启后能看到思考摘要。 */
        private String reasoningSummary;
        /** OpenAI Responses：是否让服务端保存响应（默认 false，本地回放完整历史）。 */
        private Boolean store;
        /** OpenAI 兼容：是否回传历史 reasoning_content（DeepSeek 思考模式 + 工具调用时必须）。 */
        private Boolean echoReasoning;
        private Boolean streamUsage;
        private Integer timeoutSeconds;
        private Map<String, String> headers = new LinkedHashMap<>();
        /** 透传到请求体的额外字段。 */
        private Map<String, Object> extraBody = new LinkedHashMap<>();
        /** Anthropic：是否开启自适应思考（默认按模型自动）。 */
        private Boolean thinking;
        /** Anthropic：思考显示方式 summarized / omitted。 */
        private String thinkingDisplay;
        /** Anthropic：effort（low / medium / high / xhigh / max）。 */
        private String effort;
        /** Anthropic：服务端拒答回退（默认直连官方 API 时开启）。 */
        private Boolean fallbacks;
        private Boolean eagerInputStreaming;
        private Boolean promptCache;
        private Integer maxRetries;

        /** 转成 ModelGatewayFactory 使用的 snake_case 配置。 */
        Map<String, Object> toConfig() {
            Map<String, Object> m = new LinkedHashMap<>();
            put(m, "type", type);
            put(m, "base_url", baseUrl);
            put(m, "api_key", apiKey);
            put(m, "api_key_env", apiKeyEnv);
            put(m, "max_tokens", maxTokens);
            put(m, "max_tokens_param", maxTokensParam);
            put(m, "context_window", contextWindow);
            put(m, "reasoning_effort", reasoningEffort);
            put(m, "reasoning_summary", reasoningSummary);
            put(m, "store", store);
            put(m, "echo_reasoning", echoReasoning);
            put(m, "stream_usage", streamUsage);
            put(m, "timeout_seconds", timeoutSeconds);
            if (!headers.isEmpty()) {
                m.put("headers", headers);
            }
            if (!extraBody.isEmpty()) {
                m.put("extra_body", extraBody);
            }
            put(m, "thinking", thinking);
            put(m, "thinking_display", thinkingDisplay);
            put(m, "effort", effort);
            put(m, "fallbacks", fallbacks);
            put(m, "eager_input_streaming", eagerInputStreaming);
            put(m, "prompt_cache", promptCache);
            put(m, "max_retries", maxRetries);
            return m;
        }

        private static void put(Map<String, Object> m, String k, Object v) {
            if (v != null) {
                m.put(k, v);
            }
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
        public String getMaxTokensParam() { return maxTokensParam; }
        public void setMaxTokensParam(String maxTokensParam) { this.maxTokensParam = maxTokensParam; }
        public Integer getContextWindow() { return contextWindow; }
        public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
        public String getReasoningEffort() { return reasoningEffort; }
        public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
        public String getReasoningSummary() { return reasoningSummary; }
        public void setReasoningSummary(String reasoningSummary) { this.reasoningSummary = reasoningSummary; }
        public Boolean getStore() { return store; }
        public void setStore(Boolean store) { this.store = store; }
        public Boolean getEchoReasoning() { return echoReasoning; }
        public void setEchoReasoning(Boolean echoReasoning) { this.echoReasoning = echoReasoning; }
        public Boolean getStreamUsage() { return streamUsage; }
        public void setStreamUsage(Boolean streamUsage) { this.streamUsage = streamUsage; }
        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public Map<String, Object> getExtraBody() { return extraBody; }
        public void setExtraBody(Map<String, Object> extraBody) { this.extraBody = extraBody; }
        public Boolean getThinking() { return thinking; }
        public void setThinking(Boolean thinking) { this.thinking = thinking; }
        public String getThinkingDisplay() { return thinkingDisplay; }
        public void setThinkingDisplay(String thinkingDisplay) { this.thinkingDisplay = thinkingDisplay; }
        public String getEffort() { return effort; }
        public void setEffort(String effort) { this.effort = effort; }
        public Boolean getFallbacks() { return fallbacks; }
        public void setFallbacks(Boolean fallbacks) { this.fallbacks = fallbacks; }
        public Boolean getEagerInputStreaming() { return eagerInputStreaming; }
        public void setEagerInputStreaming(Boolean eagerInputStreaming) { this.eagerInputStreaming = eagerInputStreaming; }
        public Boolean getPromptCache() { return promptCache; }
        public void setPromptCache(Boolean promptCache) { this.promptCache = promptCache; }
        public Integer getMaxRetries() { return maxRetries; }
        public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class Agent {
        /** Agent 名称（出现在系统提示词里）。 */
        private String name = "agentcore";
        private int maxTurns = 100;
        private int maxToolConcurrency = 8;
        /** 追加到系统提示词的额外要求。 */
        private String instructions = "";
        /** 整体替换默认系统提示词。 */
        private String systemPrompt;
        /** 从文件读取系统提示词（优先于 system-prompt）。 */
        private String systemPromptFile;
        /** 工具的工作目录（文件、bash 的根目录），默认进程当前目录。 */
        private Path workingDirectory;
        /** 是否读取工作目录下的 AGENTS.md 并入系统提示词。 */
        private boolean projectInstructions = true;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
        public int getMaxToolConcurrency() { return maxToolConcurrency; }
        public void setMaxToolConcurrency(int maxToolConcurrency) { this.maxToolConcurrency = maxToolConcurrency; }
        public String getInstructions() { return instructions; }
        public void setInstructions(String instructions) { this.instructions = instructions; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public String getSystemPromptFile() { return systemPromptFile; }
        public void setSystemPromptFile(String systemPromptFile) { this.systemPromptFile = systemPromptFile; }
        public Path getWorkingDirectory() { return workingDirectory; }
        public void setWorkingDirectory(Path workingDirectory) { this.workingDirectory = workingDirectory; }
        public boolean isProjectInstructions() { return projectInstructions; }
        public void setProjectInstructions(boolean projectInstructions) { this.projectInstructions = projectInstructions; }
    }

    public static class Permissions {
        /** default（按规则 + 工具默认值）/ auto（除 deny 外全部放行）/ readonly。 */
        private String mode = "default";
        private List<String> allow = new ArrayList<>();
        private List<String> deny = new ArrayList<>();
        private List<String> ask = new ArrayList<>();

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public List<String> getAllow() { return allow; }
        public void setAllow(List<String> allow) { this.allow = allow; }
        public List<String> getDeny() { return deny; }
        public void setDeny(List<String> deny) { this.deny = deny; }
        public List<String> getAsk() { return ask; }
        public void setAsk(List<String> ask) { this.ask = ask; }
    }

    public static class McpServer {
        /** stdio：命令。 */
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private String cwd;
        /** Streamable HTTP：服务地址。 */
        private String url;
        private Map<String, String> headers = new LinkedHashMap<>();
        private int timeout = 120;
        private int startupTimeout = 30;
        /** 只暴露这些工具（不设置表示全部）。 */
        private List<String> tools;
        private boolean enabled = true;

        Map<String, Object> toConfig() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (command != null) {
                m.put("command", command);
                m.put("args", args);
                m.put("env", env);
            }
            if (cwd != null) {
                m.put("cwd", cwd);
            }
            if (url != null) {
                m.put("url", url);
                m.put("headers", headers);
            }
            m.put("timeout", timeout);
            m.put("startup_timeout", startupTimeout);
            if (tools != null) {
                m.put("tools", tools);
            }
            m.put("enabled", enabled);
            return m;
        }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }
        public String getCwd() { return cwd; }
        public void setCwd(String cwd) { this.cwd = cwd; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
        public int getStartupTimeout() { return startupTimeout; }
        public void setStartupTimeout(int startupTimeout) { this.startupTimeout = startupTimeout; }
        public List<String> getTools() { return tools; }
        public void setTools(List<String> tools) { this.tools = tools; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class SubAgent {
        private String description = "";
        private String systemPrompt;
        private String instructions = "";
        /** 工具白名单（支持通配符）；不设置表示继承全部。 */
        private List<String> tools;
        /** 子 Agent 使用的模型；不设置表示与主 Agent 相同。 */
        private String model;
        private int maxTurns = 50;

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public String getInstructions() { return instructions; }
        public void setInstructions(String instructions) { this.instructions = instructions; }
        public List<String> getTools() { return tools; }
        public void setTools(List<String> tools) { this.tools = tools; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
    }

    public static class Memory {
        private boolean enabled = true;
        private String dir = HOME + "/memory";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    public static class Skills {
        private boolean enabled = true;
        private List<String> dirs = new ArrayList<>(List.of(".agentcore/skills", HOME + "/skills"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getDirs() { return dirs; }
        public void setDirs(List<String> dirs) { this.dirs = dirs; }
    }

    public static class Sessions {
        /** 关闭时会话只保存在内存里。自定义 ConversationRepository Bean（例如数据库实现）会替换默认的 JSONL 存储。 */
        private boolean enabled = true;
        private String dir = HOME + "/sessions";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    public static class Context {
        /** 估算 token 超过上下文窗口的该比例时自动压缩。 */
        private double threshold = 0.75;
        /** 绝对上限（0 表示不限）。 */
        private long maxTokens;
        /** 用于压缩摘要的模型（可用更便宜的模型）。 */
        private String summarizerModel;

        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
        public long getMaxTokens() { return maxTokens; }
        public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }
        public String getSummarizerModel() { return summarizerModel; }
        public void setSummarizerModel(String summarizerModel) { this.summarizerModel = summarizerModel; }
    }

    public static class Web {
        /** 是否注册 HTTP + SSE 接口（需要 spring-boot-starter-webmvc）。 */
        private boolean enabled = true;
        /** 接口前缀。 */
        private String basePath = "/api/agent";
        /** Web 审批等待时长，超时自动拒绝。 */
        private Duration approvalTimeout = Duration.ofMinutes(5);
        /** SSE 连接超时；0 表示不超时。 */
        private Duration sseTimeout = Duration.ZERO;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }
        public Duration getApprovalTimeout() { return approvalTimeout; }
        public void setApprovalTimeout(Duration approvalTimeout) { this.approvalTimeout = approvalTimeout; }
        public Duration getSseTimeout() { return sseTimeout; }
        public void setSseTimeout(Duration sseTimeout) { this.sseTimeout = sseTimeout; }
    }

    // ------------------------------------------------------------------ 便捷转换

    Map<String, Map<String, Object>> providerConfigs() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        providers.forEach((k, v) -> out.put(k, v.toConfig()));
        return out;
    }

    Map<String, Map<String, Object>> mcpConfigs() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        mcpServers.forEach((k, v) -> out.put(k, v.toConfig()));
        return out;
    }

    Path workingDirectory() {
        Path p = agent.getWorkingDirectory() != null ? agent.getWorkingDirectory() : Path.of(System.getProperty("user.dir"));
        return p.toAbsolutePath().normalize();
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }
    public Agent getAgent() { return agent; }
    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }
    public Permissions getPermissions() { return permissions; }
    public Map<String, McpServer> getMcpServers() { return mcpServers; }
    public void setMcpServers(Map<String, McpServer> mcpServers) { this.mcpServers = mcpServers; }
    public Map<String, SubAgent> getSubagents() { return subagents; }
    public void setSubagents(Map<String, SubAgent> subagents) { this.subagents = subagents; }
    public int getSubagentMaxDepth() { return subagentMaxDepth; }
    public void setSubagentMaxDepth(int subagentMaxDepth) { this.subagentMaxDepth = subagentMaxDepth; }
    public Memory getMemory() { return memory; }
    public Skills getSkills() { return skills; }
    public Sessions getSessions() { return sessions; }
    public Context getContext() { return context; }
    public Web getWeb() { return web; }
    public Persistence getPersistence() { return persistence; }
}
