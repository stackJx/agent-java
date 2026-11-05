# 07 接入与扩展

同一个内核，组装方式只有一套（starter 的自动装配），区别只在"谁来对接用户"。

```mermaid
flowchart TD
    core["同一个内核<br/>AgentService + AgentEngine"]
    auto["agentcore-spring-boot-starter<br/>自动装配：读 agentcore.* 组装"]
    cli["① 命令行<br/>bin/agentcore chat / run"]
    srv["② 独立 HTTP 服务<br/>bin/agentcore serve"]
    boot["③ 嵌入你的 Spring Boot 应用<br/>加一个依赖"]
    lib["④ 不用 Spring，手动 new<br/>（领域层、应用层、基础设施层都不依赖框架）"]
    cli --> app["AgentCoreApplication<br/>一个 Spring Boot 应用"]
    srv --> app
    app --> auto
    boot --> auto
    auto --> core
    lib --> core
```

| 用法 | 适合 | 入口代码 |
|---|---|---|
| 命令行 | 自己在终端里用、调试 | `bootstrap/AgentCoreApplication.java` → `interfaces/cli/ChatRepl.java` |
| 独立 HTTP 服务 | 给前端或别的语言的服务调用 | `bin/agentcore serve` → `interfaces/web/AgentController.java` |
| 嵌入 Spring Boot | 已有 Spring Boot 4 应用，要加一个智能客服、运维助手 | `spring-boot-starter/AgentCoreAutoConfiguration.java` |
| 手动组装 | 测试，或者确实不能引入 Spring 的场合 | 直接 `AgentEngine.builder()` |

## 1. 命令行

```bash
bin/agentcore                                    # 交互模式
bin/agentcore run "总结一下 README.md"            # 跑一次就退出
bin/agentcore run --events "..."                 # 每个事件输出一行 JSON，方便脚本处理
bin/agentcore -c                                 # 继续上一个会话
bin/agentcore sessions                           # 列出历史会话
bin/agentcore init                               # 生成带注释的 agentcore.yaml
```

常用选项：`-m <provider:model>`、`--yolo`（全部放行）、`--readonly`、`--no-session`、`--cwd <目录>`、`--config <文件>`。
任何 Spring 配置项都能直接从命令行覆盖：`--agentcore.web.token=xxx`、`--spring.datasource.url=...`、`--debug`（排查启动问题）。

`bin/agentcore` 是一个 Spring Boot 应用：`chat`、`run`、`sessions` 不启动 Web 服务，`serve` 才启动。日志统一写到 stderr。

启动速度：启动脚本不直接运行打包的 jar，而是运行它解压后的目录，并用 JVM 的类数据共享（CDS）缓存已经加载好的类：

| 情况 | 一次 `run` 的耗时（离线假模型） |
|---|---|
| 直接 `java -jar agentcore.jar` | 约 3.5 秒 |
| `bin/agentcore`，jar 更新后第一次（解压 + 生成缓存） | 约 6.5 秒 |
| `bin/agentcore`，之后每次 | 约 1.6 秒 |

命令行命令还只用 C1 编译器（启动快）；`serve` 是长期运行的服务，保留完整的 JIT。缓存放在 `agentcore-bootstrap/target/agentcore-app/`，jar 重新构建后自动重建；`AGENTCORE_CDS=0` 关闭。
交互模式里：`/help /new /compact /tools /usage /sessions /resume <id> /memory /exit`，运行中按 `Ctrl+C` 取消当前任务（不会退出程序）。

命令行内部怎么分工：

```mermaid
flowchart LR
    main["AgentCoreApplication<br/>启动 Spring 容器<br/>注册 ConsoleApprover / ConsolePrompter"] --> repl
    stdin["键盘输入"] --> repl["ChatRepl<br/>解析斜杠命令<br/>调用 AgentService.chat"]
    repl --> svc["AgentService"]
    svc -- "AgentEvent" --> render["ConsoleRenderer<br/>正文写 stdout<br/>过程信息写 stderr"]
    svc -- "需要审批" --> appr["ConsoleApprover<br/>[y]/[a]/[n]"]
    svc -- "ask_user 工具" --> prompt["ConsolePrompter"]
    sig["Ctrl+C"] --> sh["SignalHandling<br/>运行中转成取消"] --> svc
```

正文和过程信息分开输出，所以 `bin/agentcore run "..." > answer.txt` 只会得到答案本身。

## 2. HTTP / SSE 接口

独立部署版用 `bin/agentcore serve --port 8080` 启动（默认只监听 127.0.0.1），嵌入 Spring Boot 应用时自动注册。两者是同一个注解控制器 `AgentController`，路径前缀默认 `/api/agent`（`agentcore.web.base-path`），完整列表见项目 README 的"HTTP / SSE 接口"一节。

一次完整的交互：

```mermaid
sequenceDiagram
    autonumber
    participant FE as 前端
    participant API as HTTP 接口
    participant Svc as AgentService
    participant Eng as AgentEngine（虚拟线程）

    FE->>API: POST /conversations
    API->>Svc: startConversation()
    API-->>FE: {"id": "..."}
    FE->>API: POST /conversations/{id}/messages {"text": "帮我退款"}
    API->>Svc: chatAsync(id, input, listener)
    Svc->>Eng: 在虚拟线程上 run(...)
    API-->>FE: 响应头 text/event-stream，连接保持打开
    loop 运行过程中
        Eng-->>API: AgentEvent
        API-->>FE: event: text_delta / tool_started / ...
    end
    opt 需要审批
        API-->>FE: event: approval_required
        FE->>API: POST /approvals/{request_id} {"decision": "allow"}
    end
    opt 用户想插话或取消
        FE->>API: POST /conversations/{id}/steer 或 /cancel
    end
    Eng-->>API: RunFinished
    API-->>FE: event: run_finished，关闭连接
```

用 curl 试：

```bash
ID=$(curl -s -X POST localhost:8080/api/agent/conversations | jq -r .id)
curl -N -X POST localhost:8080/api/agent/conversations/$ID/messages \
     -H 'Content-Type: application/json' -d '{"text":"你好"}'
```

输出的每个事件长这样：

```text
event: text_delta
data: {"type":"text_delta","text":"你好"}

event: run_finished
data: {"type":"run_finished","stop_reason":"end_turn","text":"你好！有什么可以帮你？","turns":1,"usage":{...}}
```

几点要注意：

- 发消息的请求会**一直挂着**直到运行结束，前端要按流式读取（`fetch` + `ReadableStream`，或 `EventSource` 的 POST 封装）。
- 前端中途断开连接，服务端写事件失败，会自动取消这次运行。
- 经过 Nginx 时，服务端已经带了 `X-Accel-Buffering: no`，事件不会被缓冲。
- 配置了 `agentcore.web.token` 后，除 `/health` 外的请求都要带 `Authorization: Bearer <token>`。已经接入 Spring Security 的应用不用配它。

## 3. Spring Boot

加依赖（版本号以项目实际为准）：

```xml
<dependency>
    <groupId>com.agentcore</groupId>
    <artifactId>agentcore-spring-boot-starter</artifactId>
    <version>${agentcore.version}</version>
</dependency>
```

最小配置：

```yaml
agentcore:
  model: deepseek:deepseek-v4-pro
  providers:
    deepseek:
      api-key-env: DEEPSEEK_API_KEY
  agent:
    instructions: 你是电商平台的客服助手……
  tools: [todo_write]        # 只开放需要的内置工具；@AgentTool 业务工具始终可用
```

然后：

- 任意 Bean 里写 `@AgentTool` 方法，启动时自动注册为工具（`AgentToolRegistrar`）；实现了 `Tool` 接口的 Bean 也一样。
- 实现 `AgentHook` 的 Bean 自动加入钩子链，按 `@Order` 排序。
- 想替换默认组件，定义同类型的 Bean 即可，比如自己的 `ConversationRepository`、`Approver`、`ModelGateway`。
- 注入 `AgentService` 就能在自己的 Controller、定时任务、消息监听里调用 Agent。

完整例子是 `agentcore-spring-boot-sample` 模块：一个电商客服，`OrderTools` 提供查订单、退款两个工具，`RefundLimitHook` 拦截大额退款，`refund` 不是只读工具，所以前端会收到审批请求。

## 4. 不用 Spring 手动组装

领域层、应用层、基础设施层都不依赖框架，所以也可以直接 `new` 出来用，每个零件自己选。测试大多是这样写的。

```java
ConversationRepository repository = new InMemoryConversationRepository();
AgentEngine engine = AgentEngine.builder()
        .definition(new AgentDefinition("bot", "你是一个助手", EngineSettings.defaults(cwd)))
        .model(new ModelGatewayFactory(Map.of()).create("deepseek:deepseek-v4-pro"))
        .tools(new ToolRegistry(BuiltinTools.select(List.of("read_file", "grep"))))
        .permissions(RulePermissionPolicy.of("readonly", null, null, null))
        .repository(repository)
        .build();
AgentService service = new AgentService(engine, repository);
```

可运行的完整例子：`agentcore-bootstrap/src/test/java/com/agentcore/bootstrap/EmbeddingExample.java`（注解工具 + 钩子 + 审批 + 事件流），离线可跑，也是测试的一部分。

正式项目建议走 Spring：配置、插件、Bean 替换都现成，和独立部署版走的是同一条路径。

## 5. 常见扩展怎么做

| 想做的事 | 做法 | 参考 |
|---|---|---|
| 加一个业务工具 | 写 `@AgentTool` 方法；或 `FunctionTool.builder` | [04 §5](04-tools-and-permissions.md#5-定义工具的四种方式)、`OrderTools.java` |
| 换掉权限判断逻辑（按租户、角色、公司权限系统） | 实现 `PermissionPolicy` 接口，Spring 里定义同类型 Bean | [04 §2](04-tools-and-permissions.md#2-权限策略) |
| 加一条业务规则（拦截、改参数、脱敏） | 实现 `AgentHook` |
| 给所有工具调用加沙箱检查、追踪、限流、耗时统计 | 实现 `ToolInterceptor`，Spring 里定义同类型 Bean | [04 §4](04-tools-and-permissions.md#4-钩子不改引擎代码插入自己的规则) | [04 §4](04-tools-and-permissions.md#4-钩子不改引擎代码插入自己的规则)、`RefundLimitHook.java` |
| 接入公司的审批系统 / IM | 实现 `Approver`；或用 `AsyncApprover` 接一个通道 | [04 §3](04-tools-and-permissions.md#3-审批谁来回答可以吗) |
| 接一个新模型厂商 | 实现 `ModelGateway`，在 `ModelGatewayFactory` 里加 `type` | [05 §7](05-model-gateways.md#7-接一个新厂商) |
| 会话存到别的存储（Redis、Mongo…） | 实现 `ConversationRepository`：追加事件、按顺序读出重放 | [06 §2](06-persistence.md#2-共同的原理只追加事件)、`JsonlConversationRepository.java` |
| 接外部工具服务 | 配 `agentcore.mcp-servers`，不用写代码 | README 的配置一节 |
| 改系统提示词 | `agentcore.agent.system-prompt` / `system-prompt-file` 整体替换；`agentcore.agent.instructions` 追加；项目根目录放 `AGENTS.md` 自动并入 | `SystemPromptBuilder.java` |
| 让 Agent 会一类新任务 | 写一个技能：`.agentcore/skills/<名字>/SKILL.md` | [06 §6](06-persistence.md#6-技能skills) |
| 拆分任务给子 Agent | 在 `agentcore.subagents` 里定义角色（提示词、工具白名单、模型） | [04 §6](04-tools-and-permissions.md#6-子-agenttask-工具) |
| 前端展示运行过程 | 订阅 SSE 事件，按 `type` 渲染 | [02 §8](02-agent-loop.md#8-对外发出的事件) |

## 6. 测试怎么写

不需要任何 API Key：

| 想测什么 | 用什么 |
|---|---|
| 引擎逻辑（工具循环、权限、取消……） | `FakeModel`（领域层测试替身，在 `testsupport` 包）：按脚本返回预先写好的回复，见 `AgentEngineTest`；基础设施层的同类替身是 `ScriptedModelGateway` |
| 模型适配器的协议解析 | `FakeSseServer`：本地起一个假的 HTTP 服务，按顺序返回写好的 SSE 帧，并记录收到的请求，见 `ModelGatewayTest`、`OpenAiResponsesGatewayTest` |
| 存储 | `InMemoryConversationRepository`；数据库用 SQLite 临时文件或 H2，见 `JdbcRepositoryTest` |
| Spring 装配与 Web 接口 | `ApplicationContextRunner` / `@SpringBootTest`，见 `AgentCoreAutoConfigurationTest`、`AgentControllerTest`、`AgentWebTokenTest` |
| 独立部署版命令行 | 直接调 `AgentCoreApplication.run(args)`，见 `EmbeddingExampleTest` |

```bash
mvn test        # 全部测试
mvn -pl agentcore-domain test -Dtest=AgentEngineTest   # 只跑一个
```

测试与主源码使用同一套分包规则：被测类的测试放在对应包下（例如 `agent/service/AgentEngineTest`、`conversation/model/ConversationTest`），跨测试共用的测试替身放在 `testsupport` 包。领域层已按此整理，其他模块的测试同样适用。
