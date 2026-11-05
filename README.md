# agentcore-java —— 通用 Agent 底座（Java 21 · DDD）

一个**不绑定模型厂商、不绑定框架**的 Agent 底座：可以直接当命令行 / HTTP 服务用，也可以作为库嵌入任何 Java 应用。

- **DDD 分层**：领域层零第三方依赖（只用 JDK），Agent 循环、会话不变式、权限规则都是纯领域逻辑；模型、存储、MCP 都是可替换的适配器。
- **多模型**：Claude（官方 `anthropic-java` SDK）、OpenAI（Responses API）、DeepSeek / 通义千问 / Kimi / 智谱 GLM / OpenRouter / vLLM / Ollama（OpenAI Chat Completions 兼容协议）。一行 `deepseek:deepseek-v4-pro` 即可切换。
- **完整的 Agent 循环**：流式输出、工具调用、只读工具并行（虚拟线程）、各种停止原因（max_tokens / refusal / pause_turn / 超窗）、可重试错误退避、运行中取消与插话。
- **工具**：`@AgentTool` 注解方法自动推断 JSON Schema，或用 `FunctionTool` lambda；执行前统一校验参数。内置读写编辑文件、glob、grep、bash、web_fetch、todo、ask_user。
- **安全**：权限策略（allow / ask / deny 规则 + default / auto / readonly 模式）、审批端口（命令行 / Web 均已实现）、`AgentHook` 钩子；bash 规则能识别 `;`、`&&`、`$( )` 等命令拼接。
- **长任务**：上下文自动压缩、会话事件溯源式持久化（默认 JSONL 文件，可切换到 MySQL / PostgreSQL / Oracle / SQL Server / 达梦 / 金仓等关系库，崩溃可恢复）、跨会话长期记忆。
- **扩展**：Skills、子 Agent（独立上下文、并行、可换模型）、MCP（stdio 与 Streamable HTTP）。
- **接口**：交互式 REPL、一次性执行、HTTP + SSE 服务（含 Web 审批流程）。
- **Spring Boot 4 Starter**：引入依赖 + 写 `application.yml` 即可；业务工具就是带 `@AgentTool` 的 Spring Bean，钩子、审批、仓储都能用 Bean 替换。

> **第一次看这个项目？** 先看整体架构图 [`docs/agentcore-architecture.drawio`](docs/agentcore-architecture.drawio)（三页：系统总览、运行流程、配置与存储；[预览图](docs/README.md#整体架构图drawio)），再读 [docs/](docs/README.md)：7 篇带图的设计文档（架构、Agent 主循环、会话聚合、工具与权限、模型适配、存储、接入扩展），附阅读路线和术语表。每个包里的 `package-info.java` 说明了这个包负责什么、先读哪个类。

---

## 快速开始

需要 JDK 21+ 与 Maven 3.9+。

```bash
cd agentcore-java
mvn -q install                                   # 构建并运行 94 个测试，生成 agentcore-bootstrap/target/agentcore.jar

export DEEPSEEK_API_KEY=sk-...                   # 或 ANTHROPIC_API_KEY=...
bin/agentcore                                    # 交互式对话
bin/agentcore run "统计这个目录下各类文件的数量"   # 一次性执行（--events 输出 JSONL 事件）
bin/agentcore -m anthropic:claude-opus-5-5       # 指定模型
bin/agentcore -c                                 # 继续上一次会话
bin/agentcore serve --port 8080                  # 启动 HTTP + SSE 服务（接口前缀 /api/agent）
bin/agentcore init                               # 生成 agentcore.yaml 配置模板
```

没有 API Key 时可以先用离线假模型体验：`bin/agentcore -m mock`。

`bin/agentcore` 本身就是一个 Spring Boot 应用（启动脚本用 JVM 的类数据共享缓存加速：jar 更新后第一次运行约 6 秒，之后每次约 1.6 秒；`AGENTCORE_CDS=0` 可关闭）：组装全部由 `agentcore-spring-boot-starter` 完成，配置文件格式和 Spring Boot 的 `application.yml` 一样，任意配置项都可以用 `--配置项=值` 从命令行覆盖（例如 `--agentcore.web.token=xxx`）。

交互模式命令：`/help /new /compact /tools /usage /sessions /resume <id> /memory /exit`；运行中按 `Ctrl+C` 取消当前任务。

---

## 分层架构

```
        ┌───────────────────────────────────────────────────────────────────┐
        │ bootstrap（bin/agentcore，Spring Boot 应用）· spring-boot-starter（自动装配）│
        └──────────────┬───────────────┬────────────────────────────────────┘
                                      │               │
            ┌─────────────────────────▼──┐     ┌──────▼──────────────────────────────┐
            │ interfaces  接口层           │     │ infrastructure  基础设施层（适配器）    │
            │  CLI REPL / Spring MVC 接口  │     │  Anthropic SDK / OpenAI 网关           │
            └──────────────┬─────────────┘     │  内置工具 / @AgentTool / MCP 客户端    │
                           │                   │  会话与记忆仓储（文件 / JDBC）/ Skills │
            ┌──────────────▼─────────────┐     │                                      │
            │ application  应用层          │     └──────────────┬──────────────────────┘
            │  AgentService（用例编排）     │                    │ 实现端口
            │  子 Agent / 提示词 / 记忆工具 │                    │
            └──────────────┬─────────────┘                    │
                           │                                  │
            ┌──────────────▼──────────────────────────────────▼──────────────────────┐
            │ domain  领域层（零第三方依赖）                                              │
            │  conversation  Conversation 聚合根 · Message/ContentBlock 值对象 · 领域事件 │
            │  agent         AgentEngine 领域服务（主循环）· AgentEvent · AgentHook        │
            │  tool          Tool · ToolRegistry · SchemaValidator                       │
            │  permission    PermissionPolicy · PermissionRule · Approver(端口)          │
            │  llm           ModelGateway(端口) · ModelRequest/Response                  │
            │  context       ConversationCompactor · TokenEstimator                      │
            │  memory/skill  MemoryRepository(端口) · SkillCatalog(端口)                 │
            └────────────────────────────────────────────────────────────────────────┘
```

领域层每个模块内部再按 DDD 构件类型分子包：`model`（聚合、值对象）、`event`、`service`（领域服务）、`repository`（存取数据的端口）、`gateway`（调用外部系统或人的端口）、`exception`，完整结构见 [docs/01 第 3 节](docs/01-architecture.md#3-领域层的包)。

依赖方向由 Maven 模块强制：`domain ← application ← interfaces ← spring-boot-starter ← bootstrap`，`domain ← infrastructure`（基础设施只依赖领域、实现领域定义的端口）。**硬性规则只有一条：领域层不依赖任何框架**（import 只有 `java.*`），业务规则因此可以单独测试、换框架也不用动。外层统一用 Spring Boot：接口层是 Spring MVC 注解控制器，组装只有 starter 的自动装配这一套，独立部署版也是一个 Spring Boot 应用。应用层和基础设施层目前没用到 Spring，可以不借助容器手动组装（见下文"不用 Spring 手动组装"）。

| 模块 | 职责 | 主要类型 |
|---|---|---|
| `agentcore-domain` | 业务规则本身 | `Conversation`、`AgentEngine`、`Tool`、`PermissionPolicy`、`ModelGateway`、`ConversationCompactor` |
| `agentcore-application` | 用例编排：加载/保存聚合、单会话单运行、异步句柄；子 Agent；系统提示词 | `AgentService`、`TaskTool`、`SystemPromptBuilder`、`MemoryTools` |
| `agentcore-infrastructure` | 端口实现与技术细节 | `AnthropicModelGateway`、`OpenAiResponsesModelGateway`、`OpenAiCompatibleModelGateway`、`JsonlConversationRepository`、`JdbcConversationRepository`、`McpServerManager`、`AnnotatedTools` |
| `agentcore-interfaces` | 对外接口：命令行、HTTP + SSE（Spring MVC 注解控制器） | `ChatRepl`、`ConsoleApprover`、`AgentController`、`BearerTokenInterceptor` |
| `agentcore-spring-boot-starter` | Spring Boot 4 自动装配：唯一的组装方式 | `AgentCoreAutoConfiguration`、`AgentCoreProperties`、`AgentCoreWebAutoConfiguration` |
| `agentcore-bootstrap` | 独立部署版 `bin/agentcore`：Spring Boot 命令行 + HTTP 服务，可执行 jar | `AgentCoreApplication`、`agentcore-cli.yml` |
| `agentcore-spring-boot-sample` | 示例应用（电商客服），以 `spring-boot-starter-parent` 为父 POM | `OrderTools`、`RefundLimitHook` |

### 核心领域模型

**`Conversation`（聚合根）** 守护两条不变式：

1. 历史只追加，唯一的整体替换是「压缩」；
2. 助手发起的工具调用必须由紧随其后的一条用户消息**逐一**给出结果（id 一一对应），在此之前不能追加新输入或回复。

这保证历史永远是各厂商 API 都接受的合法序列，进程崩溃或取消后也能用 `repairDanglingToolCalls` 修复。所有状态变化产生领域事件（`ConversationStarted` / `MessageAppended` / `ConversationCompacted` / `UsageRecorded` / `MetadataUpdated`），仓储把**事件直接作为存储格式**追加写入，加载时回放重建。

**`AgentEngine`（领域服务）** 驱动一次运行：

```
用户输入 → [超阈值则压缩] → 调模型(流式) → 有工具调用?
   ├─ 是：参数校验 → 权限/审批 → beforeTool → 执行(只读并行) → afterTool → 回填 → 下一轮
   └─ 否：结束（或处理 max_tokens / refusal / pause_turn / 超窗）
```

它只依赖领域端口，引擎本身无状态，可被多个会话并发使用。

---

## 作为库嵌入

在 Spring Boot 应用里使用：引入 starter 即可，见下文 [Spring Boot 4 集成](#spring-boot-4-集成)。下面的工具、钩子、权限写法两种方式通用。

### 定义工具

```java
// 方式一：注解（参数 Schema 由 Java 类型推断：String/数字/布尔/枚举/List/record/Optional）
public class OrderTools {
    @AgentTool(description = "查询订单状态", readOnly = true)      // 只读：默认免审批、可并行
    public Order getOrder(@Param("订单号，例如 A1001") String orderId) { ... }

    @AgentTool(description = "给订单退款（不可逆操作）")           // 非只读：默认需要审批
    public String refund(@Param("订单号") String orderId, @Param("退款原因") Reason reason, double amount,
                         ToolContext ctx) { ... }                  // ToolContext 参数自动注入
}

// 方式二：lambda
Tool weather = FunctionTool.builder("get_weather")
        .description("查询城市天气")
        .schema(Schemas.object().string("city", "城市名", true).build())
        .readOnly(true)
        .handler((args, ctx) -> ToolOutput.text("晴 25°C"))
        .build();
```

工具里抛 `ToolException` = 返回一个错误结果给模型，模型会据此自行修正。

### 钩子、权限、审批

```java
AgentHook guard = new AgentHook() {
    @Override public HookDecision beforeTool(ToolCall call, Tool tool, Map<String, Object> args, ToolContext ctx) {
        if (call.name().equals("refund") && ((Number) args.get("amount")).doubleValue() > 100)
            return HookDecision.block("超过 100 元需人工处理");          // 模型会收到这条错误
        return HookDecision.proceed();
    }
    @Override public ToolOutput afterTool(ToolCall call, ToolOutput out, ToolContext ctx) {
        return ToolOutput.text(out.text().replace(SECRET, "***"));      // 脱敏
    }
};
PermissionPolicy policy = RulePermissionPolicy.of("default",
        List.of("bash(git status*)", "bash(ls *)"),                    // allow
        List.of("bash(rm -rf *)", "write_file(/etc/*)"),               // deny
        List.of("web_fetch"));                                         // ask
```

判定顺序：deny > ask > allow > 模式默认（只读工具允许，其余询问）。没有 Approver 时，需要询问的操作会被拒绝（适合无人值守）。对 bash，deny 命中任一子命令即拒绝；allow 要求每个子命令都被覆盖，且含 `$( )`、反引号、重定向时 allow 不生效。

`PermissionPolicy` 是接口，上面用的 `RulePermissionPolicy` 是基于配置规则的默认实现。需要按租户、角色或对接公司权限系统判断时，自己实现它（只有一个方法，可以写成 lambda），Spring Boot 里定义一个 `PermissionPolicy` 类型的 Bean 就会替换掉默认实现：

```java
@Bean
PermissionPolicy tenantPolicy(RbacClient rbac) {
    PermissionPolicy rules = RulePermissionPolicy.of("default", List.of(), List.of("bash(rm -rf *)"), List.of());
    return (tool, args) -> {
        if (!rbac.canUse(CurrentUser.id(), tool.name())) {
            return PermissionPolicy.Verdict.deny("当前用户没有 " + tool.name() + " 的权限");
        }
        return rules.check(tool, args);                                // 其余仍按配置规则判断
    };
}
```

### 不用 Spring 手动组装

```java
ConversationRepository repository = new JsonlConversationRepository(Path.of("sessions"));
AgentEngine engine = AgentEngine.builder()
        .definition(new AgentDefinition("bot", new SystemPromptBuilder().cwd(cwd).build(), EngineSettings.defaults(cwd)))
        .model(new ModelGatewayFactory(Map.of()).create("deepseek:deepseek-v4-pro"))
        .tools(new ToolRegistry(BuiltinTools.select(null)))
        .permissions(policy).approver(approver).hook(guard)
        .repository(repository)
        .build();
AgentService service = new AgentService(engine, repository);
```

完整可运行的例子见 `agentcore-bootstrap/src/test/java/com/agentcore/bootstrap/EmbeddingExample.java`（客服退款场景：注解工具 + 钩子 + 审批 + 事件流，离线可跑，并且作为测试持续验证）。

---

## Spring Boot 4 集成

```xml
<dependency>
  <groupId>com.agentcore</groupId>
  <artifactId>agentcore-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>                                   <!-- 需要 HTTP 接口时引入 -->
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
```

```yaml
spring.threads.virtual.enabled: true           # 推荐：Agent 运行与 SSE 跑在虚拟线程上
agentcore:
  model: deepseek:deepseek-v4-pro
  providers:
    deepseek: {api-key-env: DEEPSEEK_API_KEY}
  agent:
    name: 小智客服
    instructions: 你是电商平台的客服助手……
  tools: [todo_write]                          # 内置工具白名单（客服场景不开放 bash / 文件读写）
  permissions: {mode: default}
  web: {base-path: /api/agent, approval-timeout: 5m}
```

业务代码只需要写 Spring Bean：

```java
@Component
public class OrderTools {                       // 带 @AgentTool 方法的 Bean 自动注册为工具（兼容 AOP 代理）
    @AgentTool(description = "查询订单状态", readOnly = true)
    public Order getOrder(@Param("订单号") String orderId) { return orderService.find(orderId); }

    @AgentTool(description = "给订单退款（不可逆）")   // 非只读 → 前端收到 approval_required 后确认
    public String refund(@Param("订单号") String orderId, @Param("退款原因") RefundReason reason, double amount) { ... }
}

@Component
public class RefundLimitHook implements AgentHook { ... }   // AgentHook Bean 自动生效，按 @Order 排序

@Service
public class SupportFacade {
    private final AgentService agent;                         // 直接注入使用
    public RunResult ask(String conversationId, String text) { return agent.chat(conversationId, text, e -> { }); }
}
```

自动装配的内容（全部 `@ConditionalOnMissingBean`，定义同类型 Bean 即可替换）：

| Bean | 默认实现 | 常见替换 |
|---|---|---|
| `ModelGateway` | 按 `agentcore.model` 创建 | 自定义网关 / 测试用 `ScriptedModelGateway` |
| `ConversationRepository` | JSONL 文件；`agentcore.persistence.jdbc.enabled=true` 时存应用的数据源；`sessions.enabled=false` 时内存 | 其他存储（MongoDB / Redis …） |
| `Approver` | Web 应用：SSE 推送审批（`AsyncApprover`）；非 Web：无（需审批的操作被拒绝） | 接审批流（钉钉 / 飞书 / 工单） |
| `MemoryRepository` / `SkillCatalog` | 文件目录（开启 JDBC 存储时记忆也存数据库） | 配置中心等 |
| `PermissionPolicy`（接口） | `RulePermissionPolicy`：按 `agentcore.permissions.*` 规则判定 | 自己实现：按租户、角色或公司权限系统判断 |
| `ToolRegistry` | 内置工具 + 记忆 + Skills + MCP + task + 容器里的 `Tool` / `@AgentTool` Bean | — |
| `AgentEngine` / `AgentService` | 由以上组件组装；容器里的 `AgentHook`、`ToolInterceptor` Bean 自动加入 | — |
| `AgentController`（接口层） | `{base-path}` 下的 REST + SSE 接口，独立部署版用的也是它；`agentcore.web.token` 开启 Bearer 鉴权 | `agentcore.web.enabled=false` 关闭后自己写 |

配置项有完整的 IDE 提示（starter 生成了 `spring-configuration-metadata.json`）。完整示例见 `agentcore-spring-boot-sample/`：

```bash
export DEEPSEEK_API_KEY=sk-...
mvn -q install -DskipTests && mvn -pl agentcore-spring-boot-sample spring-boot:run
ID=$(curl -s -X POST localhost:8080/api/agent/conversations | jq -r .id)
curl -N -X POST localhost:8080/api/agent/conversations/$ID/messages -H 'Content-Type: application/json' -d '{"text":"订单 A1001 到哪了？"}'
```

> 依赖说明：Spring Boot 4 默认用 Jackson 3（`tools.jackson`），Anthropic SDK 用 Jackson 2（`com.fasterxml.jackson`），两者包名不同、可以共存。agentcore 的 Jackson 2 版本与 Boot 4.1 的管理版本对齐（2.21.7），示例应用的测试在 Boot 管理的依赖版本下走通了真实的 Anthropic SDK 调用链路。接口 JSON 由 agentcore 自己序列化，不受应用 Jackson 配置影响。

---

## HTTP / SSE 接口

独立部署版用 `bin/agentcore serve --port 8080` 启动（默认只监听 127.0.0.1，`--host 0.0.0.0` 对外开放）；嵌入 Spring Boot 应用时由 starter 自动注册。两者是同一个注解控制器（`agentcore-interfaces/.../web/AgentController.java`），路径前缀 `agentcore.web.base-path`，默认 `/api/agent`。配置 `agentcore.web.token` 后，除 health 外的请求都需要 `Authorization: Bearer <token>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/agent/health` | 健康检查（不需要 token） |
| GET | `/api/agent/conversations` | 会话列表 |
| POST | `/api/agent/conversations` | 新建会话 → `{"id": ...}` |
| GET | `/api/agent/conversations/{id}` | 会话详情（消息、用量、是否运行中） |
| POST | `/api/agent/conversations/{id}/messages` | `{"text": "...", "images": [...]}` → **SSE 事件流** |
| POST | `/api/agent/conversations/{id}/cancel` | 取消正在运行的任务 |
| POST | `/api/agent/conversations/{id}/steer` | `{"text": "..."}` 运行中插话 |
| GET | `/api/agent/approvals?conversationId=` | 等待中的审批 |
| POST | `/api/agent/approvals/{requestId}` | `{"decision": "allow\|always\|deny", "message": "..."}` |

SSE 事件：`run_started` `turn_started` `text_delta` `thinking_delta` `tool_call_streaming` `assistant_message` `tool_started` `tool_progress` `permission_decided` `tool_finished` `compacted` `retrying` `notice` `run_finished`，以及审批用的 `approval_required`（携带 `request_id`、工具名、参数，前端确认后调用 `/api/agent/approvals/{request_id}`，超时自动拒绝）。

```bash
bin/agentcore serve -m mock --agentcore.web.token=secret
ID=$(curl -s -X POST -H 'Authorization: Bearer secret' localhost:8080/api/agent/conversations | jq -r .id)
curl -N -X POST -H 'Authorization: Bearer secret' -H 'Content-Type: application/json' \
     localhost:8080/api/agent/conversations/$ID/messages -d '{"text":"你好"}'
```

---

## 配置

配置文件格式就是 Spring Boot 的 `application.yml`：agentcore 的配置都在 `agentcore:` 下，`spring.*`、`server.*` 照常写。嵌入 Spring Boot 应用时写在应用自己的 `application.yml` 里；独立部署版（`bin/agentcore`）按以下顺序读取，后者覆盖前者：

1. `~/.agentcore/config.yaml`（设置了 `AGENTCORE_HOME` 时为 `$AGENTCORE_HOME/config.yaml`）
2. `<工作目录>/.agentcore/config.yaml`
3. `<工作目录>/agentcore.yaml`
4. `--config <文件>`
5. 命令行参数：`-m`、`--yolo` 等快捷选项，以及任意 `--配置项=值`

独立部署版不会读取当前目录的 `application.yml`，在别的 Spring 项目目录里运行也不会串配置。`bin/agentcore init` 生成带注释的模板。引用环境变量写 `${ENV}` 或 `${ENV:默认值}`。键名推荐 kebab-case（`api-key-env`），`api_key_env` 这种下划线写法 Spring 也能识别。

```yaml
agentcore:
  model: deepseek:deepseek-v4-pro          # <provider>:<model>；不写时按 API Key 环境变量推断
  providers:
    deepseek: {api-key-env: DEEPSEEK_API_KEY}
    anthropic: {effort: high}              # thinking / thinking-display / fallbacks / eager-input-streaming 等均可覆盖
    openai: {reasoning-effort: medium}     # openai:<模型> 默认走 Responses API；reasoning-summary: auto 可显示思考摘要
    myvllm:                                # 任意 OpenAI 兼容服务：model 写成 myvllm:<模型名>
      type: openai                         # openai = Chat Completions；openai-responses = Responses API
      base-url: http://localhost:8000/v1
      api-key: EMPTY
      context-window: 131072

  permissions:
    mode: default                          # default / auto / readonly
    allow: ["bash(git status*)", "mcp__filesystem__read*"]
    deny: ["bash(rm -rf *)"]

  mcp-servers:
    filesystem: {command: npx, args: ["-y", "@modelcontextprotocol/server-filesystem", "."]}
    remote: {url: "https://example.com/mcp", headers: {Authorization: "Bearer ${TOKEN}"}}

  subagents:
    researcher:
      description: 只读调研：搜索代码/网页并给出结论
      tools: [read_file, glob, grep, web_fetch]
      model: deepseek:deepseek-flash       # 子 Agent 可以用更便宜的模型

  context: {threshold: 0.75}               # summarizer-model 可指定更便宜的压缩模型
  memory: {enabled: true, dir: ~/.agentcore/memory}
  skills: {dirs: [.agentcore/skills, ~/.agentcore/skills]}
  sessions: {dir: ~/.agentcore/sessions}
  web: {token: "${AGENTCORE_TOKEN:}", approval-timeout: 5m}

server: {port: 8080}
```

内置预设：`anthropic` `openai` `deepseek` `qwen`(dashscope) `moonshot`(kimi) `zhipu`(glm) `openrouter` `ollama` `vllm`。项目根目录的 `AGENTS.md` 会自动并入系统提示词。

**OpenAI 的两种协议**：`openai:<模型>` 默认走 Responses API（`/responses`），因为 OpenAI 新模型的函数调用只支持这个协议。默认无状态（`store: false`）：每次发送完整历史，上一轮的 output 条目（加密推理、`phase` 等）原样回放，数据只存在你自己的仓储里。要强制走旧协议，写 `type: openai-chat`；其他支持 Responses 的服务（代理、Azure 等）写 `type: openai-responses`。可调参数：`reasoning-effort`、`reasoning-summary`（auto / concise / detailed）、`store`、`max-tokens`（默认 32000，思考也计入输出）。

---

## 数据库存储

默认存文件（`~/.agentcore/sessions`、`~/.agentcore/memory`）。部署多个实例、需要按用户查询历史或统计用量时，切换到关系库。实现在基础设施层 `com.agentcore.infrastructure.persistence.jdbc`，**只依赖 JDBC（`DataSource`）**，不引入 JPA / MyBatis，不管应用用哪套数据访问框架都能接。

| 平台 | 建表脚本 | 兼容库 |
|---|---|---|
| MySQL 5.7+ | `schema-mysql.sql` | MariaDB、OceanBase（MySQL 模式）、TiDB |
| PostgreSQL 10+ | `schema-postgresql.sql` | 人大金仓 KingbaseES、openGauss、GaussDB |
| Oracle 12c+ | `schema-oracle.sql` | 达梦 DM8、OceanBase（Oracle 模式） |
| SQL Server 2016+ | `schema-sqlserver.sql` | Azure SQL |
| SQLite 3 | `schema-sqlite.sql` | 单机 / 嵌入式（真实数据库文件，已实测） |
| H2 | `schema-h2.sql` | 测试 / 嵌入式 |

三张表（前缀默认 `agent_`）：`agent_conversation`（会话摘要：标题、消息数、用量、版本号）、`agent_conversation_event`（领域事件，按序只追加，JSON 存大文本字段）、`agent_memory`（长期记忆，按 scope 隔离）。增删改查全部是标准 SQL，分页用 JDBC 的 `setMaxRows`；只有建表语句因库而异，平台按 JDBC 元数据自动识别。

**开启方式**：嵌入 Spring Boot 应用和独立部署版写法完全一样，用 Spring 的 `spring.datasource` 配数据源（连接池是 Spring Boot 默认的 HikariCP），再显式打开开关。

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/agent?useUnicode=true&characterEncoding=utf8
    username: agent
    password: ${DB_PASSWORD}
agentcore:
  persistence:
    jdbc:
      enabled: true                          # 显式开启（默认 false，避免在业务库里意外建表）
      platform: auto                         # auto / mysql / postgresql / oracle / sqlserver / sqlite / h2
      table-prefix: agent_
      initialize-schema: create-if-missing   # never：由 DBA 执行建表脚本
      memory-scope: default
```

独立部署版的 jar 里不带 JDBC 驱动，通过 `AGENTCORE_CLASSPATH` 加进来（多个用冒号分隔，整个目录写成 `/opt/drivers/*`）。单机不想装数据库时用 SQLite：

```bash
AGENTCORE_CLASSPATH=/opt/drivers/mysql-connector-j.jar bin/agentcore serve
AGENTCORE_CLASSPATH=/opt/drivers/sqlite-jdbc.jar bin/agentcore \
    --spring.datasource.url=jdbc:sqlite:/data/agent.db --agentcore.persistence.jdbc.enabled=true
```

**行为说明**

- **只建缺失的表**，不修改、不删除已有表；DBA 管理表结构时设 `initialize-schema: never`，脚本在 jar 的 `com/agentcore/infrastructure/persistence/jdbc/` 下（`${prefix}` 换成表前缀），也可以用 `JdbcSchema.script(store)` 导出。
- **乐观并发**：多个实例同时写同一会话时，后写的一方得到 `ConcurrencyConflictException`，历史不会被写乱；读取时比对版本号，被其他实例更新过会自动重新加载。
- **多实例部署**：历史存在数据库后任何实例都能读到；但正在运行的任务、等待中的审批和 SSE 连接仍在某个实例的内存里，负载均衡需按会话 ID 做粘性路由。
- **验证情况**：SQLite 在真实数据库文件上跑过完整的仓储测试；MySQL / PostgreSQL / Oracle / SQL Server 的建表脚本在 H2 对应的兼容模式下跑过同一组测试，尚未在真实的服务端上验证，上线前建议在目标库上跑一遍 `JdbcRepositoryTest`。

---

## 扩展点

| 想做的事 | 做法 |
|---|---|
| 接入新的模型协议 | 实现 `ModelGateway`：把 `ModelRequest` 转成厂商请求，流式回调 `onText/onThinking/onToolCallStart`，返回 `ModelResponse` |
| 换存储 | 关系库：开启 JDBC 存储即可；其他存储（MongoDB / Redis …）：实现 `ConversationRepository`，把 `conversation.pullEvents()` 追加写入，加载时 `Conversation.replay(events)` |
| 加业务工具 | `@AgentTool` 注解方法，或 `FunctionTool`，或实现 `Tool` 接口 |
| 接外部系统 | 写成 MCP 服务器后配置 `agentcore.mcp-servers`，或直接写工具 |
| 审计 / 脱敏 / 风控 | 实现 `AgentHook` |
| 沙箱检查 / 调用链追踪 / 限流 / 耗时统计（包住每个工具调用） | 实现 `ToolInterceptor`（拦截器链，排在参数校验和权限审批之后） |
| 接审批流（钉钉、飞书、工单…） | 实现 `Approver`（可以阻塞等待；`PermissionRequest.requestId` 用于异步回传） |
| 定制人设与流程 | `agentcore.agent.system-prompt` / `agentcore.agent.instructions` / 项目里放 `AGENTS.md` |
| 领域知识包 | skills 目录放 `<name>/SKILL.md`（frontmatter 写 name、description） |
| 多 Agent 分工 | `agentcore.subagents` 定义角色，主 Agent 用 `task` 工具派活（可并行） |
| 自定义前端 | 消费 `AgentEvent`（或 HTTP SSE），实现 `Approver` 与 `UserPrompter` |

---

## 关键设计决策

1. **领域层零依赖，规则写在领域里；外层统一用 Spring。** 工具结果配对、截断参数不执行、权限判定、压缩策略都是领域逻辑，有完整单元测试，换模型、换存储、换框架都不动它。领域之外只保留一套组装（starter 的自动装配）、一套配置（`agentcore.*`）、一套 Web 接口（注解控制器），独立部署版也是 Spring Boot 应用，便于后续写插件、做配置预热。
2. **历史只追加，原样回放。** 助手消息同时保存厂商原始内容块（`ProviderPayload`），回发给同一厂商时原样发送：Claude 的 thinking 签名、服务端拒答回退标记靠这一点保持有效；DeepSeek 思考模式在带工具的请求里要求回传历史 `reasoning_content`，适配器会自动带上；OpenAI Responses 的 output 条目（含加密推理）同样原样回放。prompt cache 是前缀匹配，所以系统提示词在会话开始时固定、工具顺序稳定，压缩采用「摘要后开启一段新对话」，而不是就地删改。
3. **事件即存储。** 会话的领域事件就是存储格式（JSONL 文件的每一行 / 数据库事件表的每一行），天然支持审计、回放与崩溃恢复；压缩前的完整历史也保留在事件里。
4. **安全约束在代码里强制执行。** 覆盖文件前必须先读、文件被外部改过要重新读、截断的工具调用绝不执行、权限规则能识别命令拼接。
5. **错误交还给模型自己修。** 未知工具、参数不合法、权限被拒、工具抛错，都作为错误结果返回，模型看到具体原因后会调整；只有不可恢复的错误才结束运行。
6. **虚拟线程 + 阻塞式代码。** 引擎用同步风格编写，易读易调试；并行工具、异步运行、HTTP 连接都跑在虚拟线程上。
7. **Claude 默认参数遵循官方建议**：默认 `claude-opus-5-5`、自适应思考（显示摘要）、`effort: high`、系统提示词与对话尾部开启 prompt cache；直连官方 API 时默认开启服务端拒答回退（`fallbacks: "default"`）和工具参数流式（`eager_input_streaming`），走代理（自定义 base_url）时两者默认关闭。OpenAI 兼容服务直接用 JDK HttpClient 实现，以便处理各家扩展字段。

---

## 测试

```bash
mvn test        # 94 个测试，不需要任何 API Key
```

| 模块 | 覆盖 |
|---|---|
| domain | 会话不变式与事件回放、权限规则（命令拼接）、参数校验与类型转换、引擎主循环（并行、错误回填、审批与记住、钩子、截断保护、pause/refusal/max_turns、重试、取消、插话、自动压缩） |
| infrastructure | 通过本地假 SSE 服务走**真实的 anthropic-java SDK**与 OpenAI 兼容解析路径（thinking 签名回放、缓存参数、beta 回退头、reasoning_content 回传、坏 JSON 回报），OpenAI Responses 流式解析（工具循环、加密推理与 phase 回放、截断/拒答/失败事件、跨厂商历史转换）、回退回放规则、内置工具、JSONL 仓储崩溃恢复、JDBC 仓储（SQLite 实测 + 其余平台建表脚本 × H2 兼容模式：回放、乐观并发冲突、过期缓存刷新、记忆隔离、100KB 大字段）、记忆与 Skills、注解工具、按 API Key 推断默认模型、MCP（stdio 分页 + 服务端请求、HTTP 会话）、子 Agent 并行与工具隔离、异步取消 |
| interfaces | 命令行 REPL（审批、deny 规则、命令）；Web 控制器由 starter 的测试覆盖 |
| bootstrap | 不用 Spring 的嵌入示例；Spring Boot 命令行端到端：run / sessions / init、配置文件读取顺序与覆盖、`--config`、启动失败的错误提示、经 `spring.datasource` 存数据库 |
| spring-boot-starter | 数据库存储的开启 / 默认关闭 / 无数据源回退、自动装配（收集 @AgentTool / Tool / AgentHook Bean、属性绑定、Bean 替换、非 Web 场景）、HTTP + SSE 全流程与 Web 审批、Bearer Token 鉴权与 health |
| spring-boot-sample | 以 Boot 4.1.2 为父 POM，经由真实 anthropic-java SDK（指向本地假 Claude 服务）跑通业务工具 + 钩子 + 审批 + SSE + 会话持久化 |

> 尚未用真实 API Key 跑过（当前环境没有 Key）。拿到 Key 后建议先跑 `bin/agentcore run "读取 README.md 并总结"` 验证。

---

## 路线图（建议的下一步）

- **WebFlux 版接口**：响应式应用可用的 `Flux<ServerSentEvent>` 控制器。
- **真实数据库回归**：用 Testcontainers 在 MySQL / PostgreSQL / Oracle / SQL Server / 达梦上跑 `JdbcRepositoryTest`。
- **多实例协同**：审批与插话通过数据库或消息队列跨实例传递，去掉粘性路由的要求；长会话加快照，减少回放事件数。
- **沙箱执行**：bash 放进容器 / bubblewrap，文件工具限定工作区根目录。
- **多租户与配额**：按租户隔离会话、记忆和权限，按模型计价并设置成本上限。
- **可观测性**：OpenTelemetry trace（每轮模型调用、每次工具调用一个 span）。
- **评测集**：常见任务的回归用例，换模型或改提示词时可量化对比。
- **更多 Provider**：Gemini、Bedrock / Vertex 上的 Claude。
