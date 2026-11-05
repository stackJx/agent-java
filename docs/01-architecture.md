# 01 架构总览

一句话：**领域层定义"Agent 怎么工作"的规则和它需要的接口（端口），基础设施层用具体技术实现这些接口，最外层用 Spring Boot 读配置、把零件拼起来、对外提供命令行和 HTTP 入口。**

## 1. 七个 Maven 模块和依赖方向

箭头表示"依赖于"。所有箭头最终都指向 `domain`，`domain` 自己不依赖任何东西。

```mermaid
flowchart BT
    domain["agentcore-domain<br/>领域层：规则 + 端口<br/>只用 java.*"]
    application["agentcore-application<br/>应用层：用例编排"]
    infrastructure["agentcore-infrastructure<br/>基础设施层：适配器<br/>anthropic-java / Jackson / JDBC"]
    interfaces["agentcore-interfaces<br/>接口层：命令行 / Spring MVC 控制器"]
    starter["agentcore-spring-boot-starter<br/>Spring Boot 自动装配<br/>唯一的组装方式"]
    bootstrap["agentcore-bootstrap<br/>独立部署版 bin/agentcore<br/>一个 Spring Boot 应用"]
    sample["agentcore-spring-boot-sample<br/>业务方示例"]

    application --> domain
    infrastructure --> domain
    interfaces --> application
    interfaces --> infrastructure
    bootstrap --> starter
    starter --> application
    starter --> infrastructure
    starter --> interfaces
    sample --> starter
```

规则：

- **硬性规则只有一条：领域层只 import `java.*`**。不碰 Spring、Jackson、JDBC、HTTP。所以核心逻辑可以单独测试，换框架也不用动它。这条由 Maven 模块强制：领域层的代码 import 了 Jackson 或 Spring，编译直接失败。
- **外层统一用 Spring Boot**：组装只有 starter 的自动装配一套，配置只有 `agentcore.*` 一套，HTTP 接口是 Spring MVC 注解控制器。独立部署版 `bin/agentcore` 本身也是 Spring Boot 应用，和嵌入业务系统时走同一条路径。后续写插件、做配置预热都可以直接用 Spring 的机制。
- 应用层和基础设施层目前没有用到 Spring（不是硬性要求，只是还不需要），所以也能不借助容器、手动 `new` 出来用，测试就是这么写的。
- **基础设施层不依赖应用层**（只在测试里用到）。它只做一件事：实现领域层定义的接口。

## 2. 端口与适配器（六边形架构）

领域层在中间，它通过"端口"（接口）声明自己需要什么能力；外面一圈是"适配器"，用具体技术实现这些端口。

```mermaid
flowchart LR
    subgraph IN["驱动方：谁来调用 Agent"]
        CLI["ChatRepl<br/>命令行"]
        MVC["AgentController<br/>Spring MVC + SSE"]
        EMB["你的代码<br/>注入 AgentService"]
    end

    subgraph APP["应用层"]
        SVC["AgentService<br/>开始会话 / 发消息 / 取消 / 插话"]
    end

    subgraph DOMAIN["领域层"]
        ENGINE["AgentEngine<br/>主循环"]
        CONV["Conversation<br/>会话聚合"]
        POLICY["PermissionPolicy<br/>权限策略"]
        P1(["ModelGateway 端口"])
        P2(["ConversationRepository 端口"])
        P3(["Approver 端口"])
        P4(["Tool 端口"])
        P5(["MemoryRepository / SkillCatalog 端口"])
    end

    subgraph OUT["被驱动方：Agent 用到的外部能力"]
        M1["AnthropicModelGateway"]
        M2["OpenAiCompatibleModelGateway"]
        M3["OpenAiResponsesModelGateway"]
        R1["JsonlConversationRepository"]
        R2["JdbcConversationRepository"]
        A1["ConsoleApprover / AsyncApprover"]
        T1["内置工具 / @AgentTool / MCP / TaskTool"]
        S1["文件或数据库里的记忆、技能目录"]
    end

    CLI --> SVC
    MVC --> SVC
    EMB --> SVC
    SVC --> ENGINE
    ENGINE --> CONV
    ENGINE --> POLICY
    ENGINE --> P1
    ENGINE --> P2
    ENGINE --> P3
    ENGINE --> P4
    P1 -.实现.- M1
    P1 -.实现.- M2
    P1 -.实现.- M3
    P2 -.实现.- R1
    P2 -.实现.- R2
    P3 -.实现.- A1
    P4 -.实现.- T1
    P5 -.实现.- S1
```

这样分的好处：

| 想做的事 | 需要改哪里 | 领域层要不要动 |
|---|---|---|
| 接一个新模型厂商 | 在 `infrastructure/llm` 加一个 `ModelGateway` 实现 | 不用 |
| 会话改存到 MongoDB | 加一个 `ConversationRepository` 实现 | 不用 |
| 审批改成发飞书卡片 | 实现 `Approver`，或给 `AsyncApprover` 接一个通道 | 不用 |
| 加一个业务工具 | 写一个 `@AgentTool` 方法或实现 `Tool` | 不用 |
| 改 Agent 的循环规则 | `AgentEngine` | 要，这正是领域层负责的事 |

## 3. 领域层的包

| 包 | 职责 | 核心类型 |
|---|---|---|
| `domain.agent` | Agent 主循环和它对外发出的事件 | `AgentEngine`、`AgentEvent`、`AgentHook`、`RunControl`、`RunResult` |
| `domain.conversation` | 会话聚合、消息、内容块、领域事件 | `Conversation`、`Message`、`ContentBlock` 的各种实现、`event/*` |
| `domain.model` | 和大模型对话的端口及请求、响应 | `ModelGateway`、`ModelRequest`、`ModelResponse`、`StopReason` |
| `domain.tool` | 工具的定义、注册、参数校验 | `Tool`、`FunctionTool`、`ToolRegistry`、`SchemaValidator` |
| `domain.permission` | 工具调用的权限判定和审批端口 | `PermissionPolicy`、`PermissionRule`、`Approver` |
| `domain.context` | 上下文长度估算与压缩 | `ConversationCompactor`、`TokenEstimator` |
| `domain.memory` / `domain.skill` | 长期记忆、技能的端口 | `MemoryRepository`、`SkillCatalog` |
| `domain.shared` | 通用基础类 | `AggregateRoot`、`DomainEvent`、`CancellationToken` |

## 4. 启动时怎么组装

不管是独立部署版还是嵌入业务系统，组装都只有一套：`agentcore-spring-boot-starter` 的自动装配。每个 Bean 都带 `@ConditionalOnMissingBean`，在自己的应用里定义同类型 Bean 就能替换默认实现。

```mermaid
flowchart TD
    props["AgentCoreProperties<br/>agentcore.* 配置"]
    factory["ModelGatewayFactory"]
    model["ModelGateway"]
    repo["ConversationRepository<br/>默认 JSONL，开启 jdbc 后用数据库"]
    memory["MemoryRepository"]
    skills["SkillCatalog"]
    mcp["McpServerManager"]
    policy["PermissionPolicy"]
    task["TaskTool"]
    registry["ToolRegistry"]
    registrar["AgentToolRegistrar<br/>扫描所有 Bean 上的 @AgentTool 方法<br/>以及实现了 Tool 的 Bean"]
    hooks["你的 AgentHook Bean"]
    engine["AgentEngine"]
    service["AgentService"]
    web["AgentController + AsyncApprover<br/>Web 应用里默认开启<br/>agentcore.web.enabled=false 可关闭"]

    props --> factory --> model --> engine
    props --> repo --> engine
    memory --> registry
    skills --> registry
    mcp --> registry
    task --> registry
    registrar --> registry
    registry --> engine
    policy --> engine
    hooks --> engine
    engine --> service --> web
    repo --> service
    web -. "AsyncApprover 作为 Approver 注入" .-> engine
```

对应代码：`agentcore-spring-boot-starter/.../AgentCoreAutoConfiguration.java`、`jdbc/AgentCoreJdbcAutoConfiguration.java`、`web/AgentCoreWebAutoConfiguration.java`。

### 独立部署版（`bin/agentcore`）怎么启动

`AgentCoreApplication` 是一个 `@SpringBootApplication`，它自己不组装任何东西，只是把命令行参数翻译成 Spring 配置，然后按命令启动容器：

```mermaid
sequenceDiagram
    autonumber
    participant Main as AgentCoreApplication.main
    participant Spring as SpringApplication
    participant Auto as starter 自动装配
    participant UI as ChatRepl / AgentController

    Main->>Main: 解析参数：-m、--yolo、--cwd……翻译成 --agentcore.xxx=...
    Main->>Spring: run(args)。serve 用 Web 模式，其余不启动 Web
    Note over Spring: 读内置的 agentcore-cli.yml，它再依次导入<br/>~/.agentcore/config.yaml → ./.agentcore/config.yaml → ./agentcore.yaml → --config
    Spring->>Auto: 创建模型、工具、仓储、引擎、AgentService
    Note over Main,Spring: chat 命令在启动前额外注册 ConsoleApprover / ConsolePrompter，<br/>审批和提问就在终端里进行
    Spring-->>Main: 容器
    Main->>UI: chat：ChatRepl 读键盘；run：跑一次就退出；serve：Tomcat 提供 HTTP 接口
```

对应代码：`agentcore-bootstrap/src/main/java/com/agentcore/bootstrap/AgentCoreApplication.java`、`agentcore-bootstrap/src/main/resources/agentcore-cli.yml`。

## 5. 一次请求穿过哪些类（概览）

```mermaid
flowchart LR
    U["用户输入"] --> I["接口层<br/>ChatRepl / AgentController"]
    I --> S["AgentService.chat / chatAsync<br/>加载会话，保证同一会话同时只有一个运行"]
    S --> E["AgentEngine.run<br/>主循环"]
    E --> M["ModelGateway.stream<br/>调模型，流式回调增量"]
    E --> T["Tool.execute<br/>执行工具"]
    E --> R["ConversationRepository.save<br/>每一步都落盘"]
    E -- "AgentEvent 事件流" --> I
```

主循环内部的细节见 [02 Agent 主循环](02-agent-loop.md)。

## 6. 几个贯穿全局的设计决策

1. **历史只追加，原样回放。** 会话里的消息只会往后加，不会就地修改。发给模型的内容在会话开始后保持稳定，模型厂商的提示缓存（prompt cache）才能持续命中，省钱也更快。唯一的"整体替换"是上下文压缩，它生成一段新的合法对话。
2. **历史永远合法。** 模型发起的每个工具调用都必须有对应的结果，否则下一次请求会被厂商拒绝。`Conversation` 在每个修改方法里检查这条规则；进程崩溃、取消、出错时会自动补上错误结果。详见 [03](03-conversation.md)。
3. **事件就是存储格式。** `Conversation` 的每次变化都产生一条领域事件，仓储只负责把事件追加写入。读取时按顺序重放事件就能还原会话。详见 [06](06-persistence.md)。
4. **引擎无状态，可并发。** 一个 `AgentEngine` 可以同时服务很多会话。每次运行的状态放在内部类 `Run` 里，工具的会话级状态按会话 ID 隔离。
5. **一切过程都是事件。** 引擎不直接打印任何东西，只发出 `AgentEvent`。命令行把事件渲染成彩色文本，HTTP 把它变成 SSE，测试把它收集起来断言。
