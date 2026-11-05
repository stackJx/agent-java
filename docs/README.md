# agentcore-java 设计文档

这组文档帮你从零读懂这个项目：它由哪几块组成、一次对话在代码里怎么走、每个设计为什么这样做。
图都用 Mermaid 画，GitHub、IntelliJ IDEA、VS Code（装 Mermaid 插件）都能直接渲染。

## 文档目录

| 文档 | 讲什么 | 适合什么时候看 |
|---|---|---|
| [01 架构总览](01-architecture.md) | 七个模块、分层和依赖方向（只有领域层不依赖框架，外层统一用 Spring）、启动时怎么把各部分拼起来 | 第一篇，先建立全局印象 |
| [02 Agent 主循环](02-agent-loop.md) | 一次运行的完整流程：调模型 → 执行工具 → 再调模型；重试、压缩、取消、插话 | 想知道"Agent 到底怎么跑起来的" |
| [03 会话聚合与事件溯源](03-conversation.md) | `Conversation` 存了什么、为什么只追加、工具调用一一对应的规则 | 想改历史、存储相关逻辑之前 |
| [04 工具、权限、审批与钩子](04-tools-and-permissions.md) | 一次工具调用经过的每一道关卡；四种定义工具的方式；子 Agent | 要加工具、做风控时 |
| [05 模型适配](05-model-gateways.md) | Claude / OpenAI Chat / OpenAI Responses 三个适配器，消息格式怎么互转 | 要接新模型、排查模型报错时 |
| [06 存储](06-persistence.md) | 会话和记忆存在哪：内存、JSONL 文件、数据库；并发写入怎么防冲突 | 部署、换数据库时 |
| [07 接入与扩展](07-integration.md) | 命令行、HTTP/SSE、嵌入 Spring Boot、不用 Spring 手动组装；常见扩展的写法 | 要把它嵌进自己的系统时 |

## 推荐阅读路线

**30 分钟版：建立整体印象**

1. 跑一次离线假模型，看一次运行会产生哪些事件：
   ```bash
   bin/agentcore run -m mock --no-session --events "你好"
   ```
2. 读 [01 架构总览](01-architecture.md) 的前两张图。
3. 读 [02 Agent 主循环](02-agent-loop.md) 的时序图和流程图。

**半天版：能改代码**

1. 按上面 30 分钟版走一遍。
2. 对照 [02](02-agent-loop.md) 读 `AgentEngine.java`（`agentcore-domain/.../agent/AgentEngine.java`），代码里的 ①②③ 编号和文档里的步骤一一对应。
3. 读 [03](03-conversation.md) 和 `Conversation.java`，理解"历史永远合法"这条规则。
4. 在 IDE 里给 `AgentEngine.Run#execute` 打断点，运行 `AgentEngineTest`，单步走一遍。
5. 需要哪块再看 04～07。

**每个包都有 `package-info.java`**：在 IDE 里打开任意包，先看这个文件，它说明了这个包负责什么、核心类是哪几个、应该先读哪个。

## 术语表

| 术语 | 含义 | 对应代码 |
|---|---|---|
| 运行（run） | 用户发一条消息后，Agent 一直工作到给出最终答复为止的整个过程，可能包含很多轮 | `AgentEngine#run` |
| 轮次（turn） | 调用一次模型算一轮。模型要求调用工具时，执行完工具会进入下一轮 | `AgentEvent.TurnStarted` |
| 工具调用 / 工具结果 | 模型在回复里说"请帮我执行某工具，参数是……"；执行后把结果作为下一条消息发回给模型 | `ToolCall` / `ToolResult` |
| 聚合根 | DDD 概念：一组必须一起保持一致的数据，外部只能通过它的方法修改。这里是一个会话 | `Conversation` |
| 领域事件 | 聚合每发生一次变化就记一条事件（新消息、压缩、用量……），存储就是把事件按顺序追加 | `conversation/event/*` |
| 端口（port） | 领域层定义的接口，说明"我需要什么能力"，不关心谁来实现 | `ModelGateway`、`ConversationRepository`、`Approver`…… |
| 适配器（adapter） | 基础设施层对端口的具体实现，比如用 HTTP 调 OpenAI、用 JDBC 写数据库 | `infrastructure/*` |
| 压缩（compaction） | 上下文快满时，把旧对话总结成一段摘要，用"摘要 + 最新消息"开启新的上下文 | `ConversationCompactor` |
| 插话（steer） | 运行中途用户追加的话，在下一个轮次边界交给模型 | `RunControl#steer` |
| 审批（approval） | 有风险的工具调用先问用户：允许一次 / 总是允许 / 拒绝 | `Approver`、`PermissionPolicy` |
| 钩子（hook） | 在模型调用、工具调用前后插入自定义逻辑（审计、拦截、改参数） | `AgentHook` |
| 子 Agent | 主 Agent 通过 `task` 工具派出去的"分身"，有独立上下文，只把结论交回来 | `TaskTool` |
| ProviderPayload | 模型厂商返回的原始内容。下次发给同一厂商时原样带回，保证思考签名等不丢 | `ProviderPayload` |

## 各模块从哪里读起

| 模块 | 作用 | 先读哪个文件 |
|---|---|---|
| `agentcore-domain` | 核心规则，零第三方依赖 | `agent/AgentEngine.java`、`conversation/Conversation.java` |
| `agentcore-application` | 用例编排 | `AgentService.java` |
| `agentcore-infrastructure` | 模型、工具、存储等适配器 | `llm/ModelGatewayFactory.java` |
| `agentcore-interfaces` | 命令行和 HTTP 接口（Spring MVC 注解控制器） | `web/AgentController.java`、`cli/ChatRepl.java` |
| `agentcore-spring-boot-starter` | Spring Boot 自动装配，唯一的组装方式 | `AgentCoreAutoConfiguration.java` |
| `agentcore-bootstrap` | 独立部署版 `bin/agentcore`，一个 Spring Boot 应用 | `AgentCoreApplication.java`、`agentcore-cli.yml` |
| `agentcore-spring-boot-sample` | 业务方使用示例 | `OrderTools.java` |
