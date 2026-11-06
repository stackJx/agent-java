# 04 工具、权限、审批与钩子

模型自己什么都做不了，它只能"说"：请帮我调用某个工具，参数是什么。真正动手的是工具。
因为工具会真的读写文件、执行命令、调用业务接口，**每一次工具调用都要经过一连串检查**，这一篇讲的就是这些检查。

## 1. 一次工具调用经过的关卡

每个调用由 `ToolExecutor` 执行：先查找工具（关卡 1），然后依次经过一条**拦截器链**（`domain/agent/interceptor/`），链的末端才真正执行工具（关卡 7）。任何一关没通过，都不会抛异常中断运行，而是生成一个**错误结果**交还给模型，让它自己调整做法。

| 关卡 | 在哪 |
|---|---|
| 1 工具存在 | `ToolExecutor` |
| 2、3 参数是 JSON、符合 Schema | `ArgumentsInterceptor` |
| 4、5 权限策略、审批 | `PermissionInterceptor` |
| （使用者自己的拦截器） | 插在这里，见本篇第 4 节 |
| 6、9 beforeTool / afterTool 钩子 | `HookInterceptor` |
| 8 截断过长输出 | `TruncateInterceptor` |
| 7 执行（超时、取消） | `ToolExecutor`，链的末端 |

```mermaid
flowchart TD
    start(["模型发起 ToolCall"]) --> g1{"1. 工具存在吗？"}
    g1 -- 否 --> e1["错误：unknown tool，附上可用工具列表"]
    g1 -- 是 --> g2{"2. 参数是合法 JSON 吗？<br/>（适配器解析时已记录 parseError）"}
    g2 -- 否 --> e2["错误：invalid tool arguments"]
    g2 -- 是 --> g3{"3. 符合工具的 JSON Schema 吗？<br/>SchemaValidator，顺带温和转换类型<br/>比如字符串 &quot;3&quot; 转成数字 3"}
    g3 -- 否 --> e3["错误：列出最多 10 条校验失败原因"]
    g3 -- 是 --> g4{"4. 权限策略<br/>PermissionPolicy.check"}
    g4 -- DENY --> e4["错误：Permission denied，不要重试同样的操作"]
    g4 -- ASK --> g5{"5. 审批<br/>Approver.approve"}
    g5 -- "没有审批人（无人值守）" --> e4
    g5 -- 拒绝 --> e4
    g5 -- "总是允许" --> remember["为当前会话生成 allow 规则记住"] --> g6
    g5 -- 允许一次 --> g6
    g4 -- ALLOW --> g6{"6. beforeTool 钩子"}
    g6 -- 拦截 --> e6["错误：Blocked by policy hook，附钩子给的原因"]
    g6 -- "放行（可能改写了参数）" --> run["7. 在虚拟线程上执行<br/>超时默认 10 分钟，可被取消"]
    run -- "ToolException" --> e7["错误结果：异常信息（让模型自己修正）"]
    run -- "其他异常 / 超时" --> e7
    run --> trunc["8. 输出太长就截掉中间<br/>默认保留 30000 字符"]
    e7 --> trunc
    trunc --> after["9. afterTool 钩子<br/>可以脱敏、改写输出"]
    after --> done(["ToolResult 回填到会话"])
    e1 --> done
    e2 --> done
    e3 --> done
    e4 --> done
    e6 --> done
```

"把错误交还给模型"而不是抛异常，是 Agent 能自我纠正的关键：模型看到 `Error: invalid arguments for edit_file: - old_string is required`，下一轮通常就会改对。

## 2. 权限策略

`PermissionPolicy`（`domain/permission/service/`）对每次调用给出三种结论之一：**允许（ALLOW）/ 拒绝（DENY）/ 询问（ASK）**。它是一个接口，默认实现 `RulePermissionPolicy` 按配置的规则判断，下面讲的判定顺序、模式、规则写法都是这个默认实现的行为；也可以整个换掉，见本节最后。

### 判定顺序

```mermaid
flowchart TD
    in(["工具 + 参数"]) --> subj["算出匹配对象 subject<br/>bash 取命令，web_fetch 取 URL<br/>文件工具取解析后的绝对路径（去掉 ..、跟随符号链接）"]
    subj --> d{"命中 deny 规则？"}
    d -- 是 --> DENY(["DENY"])
    d -- 否 --> a{"命中 ask 规则？"}
    a -- 是 --> ASK(["ASK"])
    a -- 否 --> al{"命中 allow 规则？"}
    al -- 是 --> ALLOW(["ALLOW"])
    al -- 否 --> mode{"权限模式"}
    mode -- auto --> ALLOW
    mode -- "readonly 且工具会改东西" --> DENY
    mode -- "default 或 readonly 的只读工具" --> def{"工具默认值<br/>tool.defaultPermission()"}
    def -- "默认放行，且没有访问工作目录之外" --> ALLOW
    def -- "默认询问（含 web_fetch），或访问工作目录之外" --> ASK
```

没有规则命中时用工具的默认值，另外有两条补充：
- 默认放行的文件工具（`read_file`、`glob`、`grep`）访问**工作目录之外**的路径（例如 `~/.ssh`、`/etc`）改为询问，按真实路径比较，指向外面的符号链接也算外面；
- `web_fetch` 虽然只读，但会把数据发到外面，默认每次都问。

优先级是 **deny > ask > allow > 模式默认值**：只要命中一条 deny，什么 allow 都救不回来。

### 三种模式

| 模式 | 配置写法 | 行为 | 适合 |
|---|---|---|---|
| `default` | `agentcore.permissions.mode: default` | 默认免审批的调用直接放行，其他询问 | 有人在旁边看着 |
| `auto` | `auto` / `yolo`，命令行 `--yolo` | 除了 deny 规则，全部放行 | 沙箱、容器、无人值守 |
| `readonly` | `readonly`，命令行 `--readonly` | 会改东西的工具一律拒绝；只读工具按默认值放行或询问 | 只想让它看看、不许改 |

### 规则写法

写法是 `工具名(匹配模式)`，两部分都支持 `*` 通配符，省略括号表示匹配该工具的所有调用：

```yaml
agentcore:
  permissions:
    allow:
      - "bash(git status*)"        # 允许 git status 开头的命令
      - "bash(mvn test*)"
      - "mcp__filesystem__read*"   # 允许某个 MCP 服务器的所有读工具
      - "web_fetch(https://docs.example.com/*)"  # 这个网站的页面不用每次都问
      - "write_file(src/*)"        # 相对路径按工作目录算：只放行工作目录下 src 里的文件
    deny:
      - "bash(rm -rf *)"
      - "write_file(/etc/*)"
    ask:
      - "read_file(*.env)"         # 以通配符开头的匹配任何位置：所有 .env 都要问
```

**文件工具按路径匹配**（声明了路径参数的工具：`read_file`、`write_file`、`edit_file`、`glob`、`grep`）。直接拿参数里的原始写法去匹配不安全：`src/../../etc/passwd` 能匹配 `write_file(src/*)`，`../../etc/passwd` 又躲得过 `write_file(/etc/*)`。所以匹配前两边先换成同一种形式（`permission/service/FilePathRules`）：

- 调用里的路径：按工作目录解析成绝对路径，去掉 `.` 和 `..`，跟随符号链接（文件还不存在时，解析最近的存在的上级目录）；
- 规则里的模式：相对路径按工作目录补全；第一个通配符之前的目录部分同样跟随符号链接（例如 macOS 上 `/etc` 其实是 `/private/etc`）；以通配符开头的模式（`*.env`）原样使用，匹配任何位置。

所以 `write_file(src/*)` 不再放行 `src/../../etc/passwd`，也不放行指向外面的 `src/link/x`；`write_file(/etc/*)` 拦得住 `../../etc/passwd`。

`bash` 有额外的安全处理，防止用一条 allow 规则偷带危险命令：

- 命令按 `;` `&&` `||` `|` `|&` `&`（后台执行）换行 拆成多个子命令；
- **deny**：任意一个子命令命中就拒绝；
- **allow**：每个子命令都要被某条 allow 覆盖才算放行；
- 命令里有 `$(` `` ` `` `>` `<` 时，allow 规则不生效，转为询问。

所以 `bash(git status*)` 不会放行 `git status && rm -rf /`，也不会放行 `git status & rm -rf /`。

**deny 规则只是字符串匹配**：`rm  -rf`（两个空格）、`/bin/rm -rf`、`sh -c '…'` 都能绕开，只能当作提醒，不能当作安全边界。真正的边界是 allow 白名单加人工审批（或者在容器、沙箱里跑）。

用户在审批时选"总是允许"，`RulePermissionPolicy#remember` 会**为当前会话**生成尽量具体的规则（只在当前进程里有效，其他会话、其他用户不受影响）：

- bash 每个子命令一条：第二个词不是选项时记成"命令 + 子命令"，例如 `npm test` → `bash(npm test*)`（`npm publish` 照样要问）；第二个词是选项时只放行完全相同的命令，例如 `rm -rf build` → `bash(rm -rf build)`；
- 其他工具记成整个工具名。

### 换成自己的权限判断

`PermissionPolicy` 接口只有一个必须实现的方法 `check(工具, 参数)`，返回允许 / 询问 / 拒绝和原因；`remember`（用户选"总是允许"时调用）有默认实现：不记住。引擎实际调用的是带 `ToolContext` 的重载 `check(工具, 参数, 上下文)` / `remember(工具, 参数, 上下文)`（能拿到工作目录、会话 ID），它们默认转给不带上下文的版本，所以只写一个 lambda 也能用。

```java
PermissionPolicy rules = RulePermissionPolicy.of("default", List.of(), List.of("bash(rm -rf *)"), List.of());
PermissionPolicy tenantPolicy = (tool, args) -> {
    if (!rbac.canUse(CurrentUser.id(), tool.name())) {
        return PermissionPolicy.Verdict.deny("当前用户没有 " + tool.name() + " 的权限");
    }
    return rules.check(tool, args);   // 其余仍按配置规则判断
};
```

Spring Boot 里定义一个 `PermissionPolicy` 类型的 Bean 就会替换掉默认实现；手动组装时传给 `AgentEngine.builder().permissions(...)`。实现要线程安全，并行执行的工具会同时调用它。

**和钩子怎么选**：`PermissionPolicy` 决定"这个工具能不能用、要不要问人"，结果可以是"询问"，会走审批流程；`AgentHook.beforeTool` 在审批之后、执行之前运行，适合按参数做业务拦截或改写参数。

## 3. 审批：谁来回答"可以吗"

审批是一个端口 `Approver`，必须实现的方法是 `approve(request) → Approval`，可以阻塞到用户做出决定。引擎调用的是可取消的重载 `approve(request, cancel)`：默认忽略取消令牌（命令行在提示下按 Ctrl+C 就是拒绝），`AsyncApprover` 覆盖了它，运行被取消时立刻结束等待。

| 实现 | 位置 | 怎么问 |
|---|---|---|
| `ConsoleApprover` | `interfaces/cli` | 命令行提示 `[y]允许 / [a]总是允许 / [n]拒绝`，也可以直接输入拒绝理由，理由会交给模型 |
| `AsyncApprover` | `application/approval` | 通过"通道"把请求推给前端（SSE、WebSocket、IM 卡片），等前端回调 `resolve()`；超时或运行被取消就拒绝 |
| 不配置 | | 所有 ASK 一律当成 DENY（无人值守时最安全的默认值） |

`AsyncApprover` 在 Web 场景下的时序：

```mermaid
sequenceDiagram
    participant FE as 前端
    participant HTTP as AgentController
    participant AA as AsyncApprover
    participant Eng as AgentEngine（运行线程）

    FE->>HTTP: POST /conversations/{id}/messages
    HTTP->>Eng: chatAsync(...)：先占住会话（已在运行就返回 409）
    Eng->>AA: 占住之后才 attach(id, 通道：往这个 SSE 连接推事件)
    Eng->>AA: approve(request, cancel)，运行线程在这里阻塞
    AA->>HTTP: 通道.requestApproval(request)
    HTTP-->>FE: SSE 事件 approval_required {request_id, tool, summary}
    FE->>HTTP: POST /approvals/{request_id} {"decision": "allow"}
    HTTP->>AA: resolve(request_id, allow)
    AA-->>Eng: Approval.allow()，运行继续
    Note over AA: 超时（默认 300 秒）、前端断开或运行被取消：返回拒绝
    HTTP->>AA: 运行结束后 detach(id, 这次的通道)
```

**同一个会话**里多个工具同时需要审批时，引擎让它们**排队**，一次只弹一个，避免多个提示交错。不同会话的审批互不影响：引擎是所有会话共用的，队列按会话 ID 分开，没人排队时自动移除。排队和等待审批时都可以被取消。

## 4. 钩子：不改引擎代码，插入自己的规则

`AgentHook` 有四个方法，都有默认实现，按需覆盖：

| 方法 | 时机 | 能做什么 | 典型用途 |
|---|---|---|---|
| `beforeModel(request)` | 每次调模型前 | 返回修改后的请求 | 注入实时上下文、按租户换参数 |
| `afterModel(response)` | 每次模型回复后 | 只读观察 | 记录用量、审计 |
| `beforeTool(call, tool, args, ctx)` | 权限通过后、执行前 | 放行 / 拦截（原因交给模型）/ 改写参数 | 业务风控、参数补全 |
| `afterTool(call, output, ctx)` | 执行后 | 返回修改后的输出 | 脱敏、追加提示 |

示例（`agentcore-spring-boot-sample/.../RefundLimitHook.java`）：单笔退款超过 100 元转人工。

```java
@Component
public class RefundLimitHook implements AgentHook {
    @Override
    public HookDecision beforeTool(ToolCall call, Tool tool, Map<String, Object> args, ToolContext ctx) {
        if (call.name().equals("refund") && args.get("amount") instanceof Number n && n.doubleValue() > 100) {
            return HookDecision.block("单笔退款超过 100 元需要人工客服处理，请告知用户已转人工");
        }
        return HookDecision.proceed();
    }
}
```

模型会收到 `Blocked by policy hook: 单笔退款超过 100 元……`，然后自然地告诉用户已转人工。

### 拦截器：更底层的扩展点

钩子是给业务方的简单接口；需要包住整个执行过程（计时、追踪、限流、沙箱检查）时，实现 `ToolInterceptor`：

```java
ToolInterceptor sandbox = (inv, chain) -> {
    if (inv.arguments().get("path") instanceof String p && p.startsWith("/etc/")) {
        return ToolOutput.error("Blocked by sandbox: outside workspace");   // 拦下：错误交给模型
    }
    long t0 = System.nanoTime();
    ToolOutput out = chain.proceed(inv);                                    // 放行，执行后面的链
    metrics.record(inv.tool().name(), System.nanoTime() - t0);              // 执行后加工
    return out;
};
AgentEngine.builder()....interceptor(sandbox)    // Spring Boot 里定义 ToolInterceptor 类型的 Bean 即可
```

使用者的拦截器排在参数校验、权限审批之后，所以看到的都是参数合法、已经批准的调用。子 Agent 复制父引擎时会带上它们。

**权限和钩子的分工**：权限规则是通用的、可配置的"能不能用这个工具"；钩子是写在代码里的业务规则，能看到解析后的参数，判断更细。

## 5. 定义工具的四种方式

所有工具最后都是领域接口 `Tool`（`domain/tool/model/Tool.java`）的实现：

```mermaid
classDiagram
    direction LR
    class Tool {
        <<interface>>
        name() String
        description() String
        inputSchema() Map
        execute(args, ctx) ToolOutput
        readOnly() boolean
        concurrencySafe() boolean
        defaultPermission() PermissionLevel
        timeout() Duration
        maxOutputChars() int
        permissionPath(args) String
        permissionSubject(args) String
    }
    class FunctionTool { 用 builder 拼出来的工具 }
    class AnnotatedTools { 扫描 @AgentTool 方法生成 FunctionTool }
    class McpTool { 远端 MCP 工具，名字 mcp__服务器__工具 }
    class TaskTool { 子 Agent }
    Tool <|.. FunctionTool
    Tool <|.. McpTool
    Tool <|.. TaskTool
    AnnotatedTools ..> FunctionTool : 生成
```

**方式一：`@AgentTool` 注解（推荐写业务工具）**。参数的 Schema 从 Java 类型推断，枚举会变成 `enum`，record 会变成嵌套对象：

```java
@AgentTool(description = "查询订单状态、金额和物流单号", readOnly = true)
public Order getOrder(@Param("订单号，例如 A1001") String orderId) { ... }
```

在 Spring Boot 里，Bean 上有这种方法就会被自动注册；不用 Spring 时调用 `AnnotatedTools.from(对象)`。

**方式二：`FunctionTool.builder`**（内置工具都这样写）：

```java
Tool lookup = FunctionTool.builder("lookup")
        .description("按 key 查值")
        .readOnly(true)
        .schema(Schemas.object().string("key", "要查的 key", true).build())
        .handler((args, ctx) -> ToolOutput.text("value-of-" + args.get("key")))
        .build();
```

**方式三：直接实现 `Tool` 接口**，需要完全控制时用，比如 `TaskTool`。

**方式四：MCP 服务器**。在配置里写 `agentcore.mcp-servers`，启动时连接、列出远端工具，每个包装成一个 `McpTool`。远端标了 `readOnlyHint` 的会被当成只读工具，但这只是服务器的自我声明（MCP 规范要求不要据此做安全决策），所以默认只用来决定能否并行执行，权限照常询问；在服务器配置里写 `trust_annotations: true`，标了只读的工具才免审批。工具名替换非法字符、截到 64 个字符后撞名的，会加 `_2`、`_3` 后缀。协议用官方 MCP Java SDK 实现，支持子进程（`command` / `args` / `env` / `cwd`）和 Streamable HTTP（`url` / `headers`）两种连接方式，`McpServerManager` 负责按配置并发启动、收集工具。

### 写工具时要注意

| 属性 | 默认值 | 影响 |
|---|---|---|
| `readOnly` | false | 决定能否与其他只读工具并行，以及默认是否免审批 |
| `permissionPath(参数)` | null | 这次调用要访问的文件路径。返回非 null 时，权限规则按解析后的绝对路径匹配，读工作目录之外要询问。`FunctionTool` 用 `.pathArgument("path")` 声明（搜索类工具 `.pathArgument("path", ".")`：没传时就是工作目录） |
| `timeout` | 10 分钟 | 超时会中断执行线程。自己控制超时的工具（如 `bash`）返回 null |
| `maxOutputChars` | 30000 | 超出后截掉中间部分，防止撑爆上下文 |
| 抛 `ToolException` | | 表示"模型可以自己修正的错误"，消息会原样交给模型 |
| `ToolContext` | | 拿工作目录、上报进度（`report`）、会话级共享状态（`state`）、取消信号、宿主注入的服务 |

### 内置工具

代码在 `infrastructure/tool/builtin/`：`FileTools`（读写文件）、`SearchTools`（glob / grep）、`ShellTools`（bash）、`WebTools`（web_fetch）、`InteractionTools`（todo_write / ask_user）。

| 工具 | 只读 | 作用 |
|---|---|---|
| `read_file` | ✓ | 读文件（带行号，支持分段；逐行读，单行超过 2000 字符的部分不显示）。工作目录之外默认询问 |
| `glob` / `grep` | ✓ | 按文件名 / 内容搜索；结果边读边截，只保留 max_results 行。工作目录之外默认询问 |
| `web_fetch` | ✓（默认询问） | 抓网页：最多读 5 MB；默认拒绝本机、内网、链路本地地址（每一跳重定向都检查），`agentcore.web-fetch.allow-private-network: true` 放开 |
| `write_file` / `edit_file` | | 写文件 / 精确替换（`old_string` 不能为空；只改 UTF-8 文件，GBK 等编码会给出明确错误） |
| `bash` | | 执行命令，自带超时（默认 120 秒，最长 600 秒）；输出只保留开头和结尾各 256 KB |
| `todo_write` | 免审批 | 维护任务清单 |
| `ask_user` | 免审批 | 向用户提问（需要宿主提供 `UserPrompter`） |
| `memory_save` / `memory_read` / `memory_search` / `memory_delete` | 读、搜只读；写、删免审批 | 长期记忆 |
| `load_skill` | ✓ | 加载一个技能的完整说明 |
| `task` | 免审批 | 派出子 Agent |

## 6. 子 Agent（`task` 工具）

主 Agent 遇到"需要大量搜索、阅读"的子任务，可以调用 `task`，把它交给一个**有独立上下文**的子 Agent。子 Agent 的中间过程不会进入主 Agent 的上下文，主 Agent 只拿到最后的报告。

```mermaid
sequenceDiagram
    participant Main as 主 Agent 引擎
    participant Task as TaskTool
    participant Child as 子 Agent 引擎
    participant Model as 模型

    Main->>Task: execute({description, prompt, agent: "researcher"})
    Note over Task: 检查嵌套深度（agentcore.subagent-max-depth，默认 1）
    Note over Task: 复制父引擎（toBuilder）：<br/>同一套权限策略、审批人、钩子<br/>工具按角色白名单过滤，去掉 ask_user<br/>到达最大深度时去掉 task<br/>不保存会话（repository = null）
    Task->>Child: run(新会话, prompt)
    loop 子 Agent 自己的多轮循环
        Child->>Model: stream(...)
        Child->>Child: 执行工具（进度通过 ctx.report 回传给主 Agent）
    end
    Child-->>Task: RunResult（最终报告 + 用量）
    Task-->>Main: ToolOutput（报告文字，用量放在 metadata 里）
    Note over Main: 把子 Agent 的用量计入主会话
```

几个要点：

- 模型在一次回复里发起多个 `task`，它们是可并行工具，会**同时运行**。
- 子 Agent 无法绕过权限：它的每个工具调用照样经过上面的全部关卡。
- 每个角色可以配置自己的提示词、工具白名单、模型（比如用更便宜的模型做搜索）：

```yaml
agentcore:
  subagents:
    researcher:
      description: 只读调研：搜索代码/网页并给出结论
      tools: [read_file, glob, grep, web_fetch]
      model: deepseek:deepseek-flash
```
