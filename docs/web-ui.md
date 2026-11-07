# 默认 Web 页面

AgentCore 自带一个轻量对话页面，布局参考 DSH：左侧会话列表、中间对话、底部输入框，配合工具卡片和审批卡片。它不是 DSH 的移植版，不依赖 DSH 服务、React、CDN 或前端构建工具。

## 启动

```bash
mvn install
bin/agentcore serve --port 8080 -m mock
```

访问 <http://127.0.0.1:8080/>，独立部署版会跳转到 `/agentcore/`。`mock` 是离线假模型；可以先离线启动，再从页面的「模型设置」添加真实模型，无须重启。原有 YAML / 环境变量 provider 配置仍可使用。

引入 starter 的 Spring MVC 应用默认在 `/agentcore/` 提供页面，但**不会接管宿主应用的 `/`**。运行不需要 Node；页面资源由 Maven 原样打包到 JAR。

## 能做什么

- 新建、切换、删除会话；侧栏按“今天 / 昨天 / 最近 7 天 / 更早”分组，可按标题搜索；恢复历史，显示 token 用量。
- 流式正文、折叠思考、常用 Markdown、代码块复制；最终回答下方可复制整段 Markdown 原文。
- 展开工具参数和结果；审批允许一次、本会话总是允许、拒绝及拒绝原因。审批卡片显示在对应的工具卡片里（`approval_required` 事件带 `call_id`），回答后收起，工具卡片标题行显示“已批准 / 已拒绝”；权限原因翻译成中文说明，工具默认需要确认时不显示原因。
- 运行中停止或插话；切换到其他会话不会丢失正在运行的页面状态。
- 按会话暂存草稿，提交失败不清空输入；图片支持选择/粘贴 PNG、JPEG、GIF、WebP，每条最多 4 张、每张 5 MB（这是页面限制，不替代服务端资源配额）。
- 输入区动态选择模型；管理员在线添加、编辑、删除模型配置及更改默认模型。
- 亮暗主题、窄屏侧栏、键盘操作。
- 页面刷新后查询待审批列表；其他连接启动的运行通过轮询检查结束状态，再恢复已保存的历史。

### 明确的边界

- 一个标签页同时发起一个运行；运行中切换会话可以查看历史，不能从该标签页再启动第二个运行。
- 刷新/关闭页面会断开 SSE；后端可能取消原运行。页面没有断线续传协议，网络中断后应先刷新历史确认结果，不要直接重发。
- 插话只支持文字，拒收时保留输入，不自动开始新运行以免与收尾阶段冲突。
- 历史图片默认显示占位信息，不自动拉取 base64 或远程图片；即时发送的本地图片可预览。
- Markdown 是受控的常用子集，不执行模型提供的 HTML；不包含完整的 GFM、公式和语法高亮引擎。
- 工具输出暂为通用卡片，不实现 DSH 的文件树、终端、插件市场、专业任务工作台等功能。
- `ask_user` 等业务工具的专用交互不在此页范围内；此页的审批卡片专门处理权限确认。

## 动态模型设置与选择

1. 点击左下的模型设置按钮（滑块图标），在“自定义模型”里点“新建”，填写模型配置：ID、显示名称、协议、模型名称、Base URL、API Key；可选填写输出上限和上下文窗口。
2. 保存后在输入区的模型下拉框选择它，再发送消息。配置保存本身不会调用模型、不会产生模型推理费用；实际发送消息才请求配置的服务。
3. 管理面板可设定默认模型。内置「启动模型」保留启动时的配置，「离线 mock」用于无网络体验；二者不可编辑或删除。

支持 Anthropic Messages、OpenAI Chat Completions 兼容服务、OpenAI Responses 和离线 mock。兼容服务需填写其 API 根地址，例如本地 Ollama 的 `http://localhost:11434/v1`；协议选择 `openai`（Chat Completions），不要把 `/chat/completions` 填入 Base URL。不同供应商是否支持图片/思考/工具调用仍由其协议能力决定，本页不会自动下载供应商模型列表或验证账号余额。

### 保存与生效范围

- 每个自定义配置是一份独立的模型连接设置，不继承启动配置里隐藏的 API Key、自定义请求头或额外参数。使用启动配置的高级选项时，选择「启动模型」。
- 密钥输入框留空表示保留原值，勾选「删除已保存的 API key」才删除。修改目标地址的 origin（协议/主机/端口）或协议类型时，必须重新提供密钥或明确清除，避免旧密钥被送到新服务。
- **配置含明文 API Key，仅保存在服务器本地文件；不会经查询接口返回，也不会写入浏览器存储。** POSIX 平台新建专用目录权限为 `0700`、文件为 `0600`；请同样保护备份和服务器账号。非 POSIX 平台需管理员配置等效 ACL。
- 默认文件位置为 `~/.agentcore/web-models/models.json`。可用 `agentcore.web.models.file` 指定路径；空字符串启用仅内存模式，重启后丢失。自定义文件须位于专用私有目录，程序不会修改已有共享目录的权限。此文件不是原有 YAML，不会覆盖原配置。
- 文件读不了或内容不对时（不是合法 JSON、权限不是 600/700、路径上有符号链接、有多个硬链接、某个配置不合法），`serve` 会拒绝启动，错误信息给出文件路径和具体原因（不含文件内容），例如 `could not load model settings from ~/.agentcore/web-models/models.json: file must only be readable by its owner (chmod 600)`。修好或删除这个文件即可；保存失败时页面只显示固定的错误，具体原因写在服务端日志里。
- 修改、删除配置只影响之后开始的运行；正在生成、审批或插话的运行保持原模型快照。普通用户只能选择已登记的配置，不能提交任意服务地址或读取/修改配置。
- 同一会话可在两条消息间切换模型；运行中模型选择器锁定。最近使用的模型 ID 随会话元数据保存；当前标签页的未提交选择随草稿暂存。模型被删除时提示回退，保留输入并要求再次确认发送。
- 自动上下文压缩及未显式指定模型的子 Agent 跟随本次选模；显式指定的摘要模型、子 Agent 角色模型和手动压缩入口继续使用原服务配置。
- 这是单进程设置文件，不提供多实例同步或文件级多写者协调；不同实例应使用不同配置文件。

### HTTP 接口

以下路径相对于 `agentcore.web.base-path`：

| 方法与路径 | 用途 |
|---|---|
| `GET /models` | 可选择模型目录、默认 ID、当前调用者是否可管理；不含服务地址/密钥 |
| `GET /model-settings` | 管理员查看自定义配置；密钥只返回 `has_api_key` |
| `PUT /model-settings/{id}` | 管理员新增/替换配置；ID 为 `[a-z][a-z0-9_-]{0,63}` |
| `DELETE /model-settings/{id}` | 管理员删除；删除当前默认项后回到启动模型 |
| `PUT /model-settings/default` | 管理员设置默认；请求 `{"model_id":"my-model"}` |
| `POST /conversations/{id}/messages` | 原有输入加可选 `model_id`；省略时采用当前目录默认值 |

设置 `agentcore.web.models.enabled: false` 可关闭目录与管理接口，页面退回启动模型模式。普通聊天 API 保持兼容；无目录时传入 `model_id` 会被拒绝，不会被静默忽略。

## 配置与鉴权

```yaml
agentcore:
  web:
    base-path: /api/agent
    # token: ${AGENTCORE_TOKEN}
    models:
      enabled: true
      file: ~/.agentcore/web-models/models.json  # 空字符串为仅内存模式
    ui:
      enabled: true
      path: /agentcore
      redirect-root: false   # starter 默认 false；独立 serve 默认 true
```

- `ui.enabled: false` 关闭页面和静态资源，不关闭 API。
- 自定义 `ui.path`、`base-path` 或 `server.servlet.context-path` 后，入口按当前请求注入 API 地址。UI/API 路径须是非根绝对路径，不能互相包含；拒绝编码路径、`..`、重复斜杠、通配符、查询参数和片段。
- 页面壳本身无需令牌，API 仍受原来的本机访问限制或 Bearer token 校验保护；页面可填写 token。
- token 仅保存在当前标签页的 `sessionStorage`，按 API 前缀区分；无法访问存储时只保留在内存。不会放在 URL，也不会发送给外部模型。主题偏好保存在 `localStorage`。
- 切换 token 前须结束当前运行；切换身份会清空本页消息、草稿、模型选择和密钥表单。共享 token 仍是管理员 token，普通用户应使用 `user-tokens`。
- 模型管理仅允许共享管理员 token、通过本机保护校验的单用户请求，或宿主认证中具备 `AGENTCORE_ADMIN` 角色的用户。即使关闭本机保护，也不会因此开放匿名模型管理。会话所有权隔离不等于文件系统租户隔离：工具的文件访问权限仍需单独约束，不能把保存密钥的目录暴露给不可信工具或用户。
- 远程部署请使用 HTTPS 和明确认证。嵌入有 Spring Security 的应用时，由宿主应用处理登录、CSRF 等安全策略；此页面不自动更改宿主安全配置。
- Nginx 等反向代理应对消息接口禁用响应缓冲，保证 SSE 及时到达。

## 代码分工

- [AgentUiController](../agentcore-interfaces/src/main/java/com/agentcore/interfaces/web/AgentUiController.java)：页面入口与 API 前缀注入。
- [页面模板](../agentcore-interfaces/src/main/resources/agentcore-ui/index.html)、[样式](../agentcore-interfaces/src/main/resources/agentcore-ui/css/app.css)：结构、主题和响应式布局。
- [app.js](../agentcore-interfaces/src/main/resources/agentcore-ui/js/app.js)：导航、草稿、身份和运行生命周期。
- [api.js](../agentcore-interfaces/src/main/resources/agentcore-ui/js/api.js)：HTTP 和 POST SSE。
- [models.js](../agentcore-interfaces/src/main/resources/agentcore-ui/js/models.js)：模型目录、选择器和安全设置表单。
- [ModelController](../agentcore-interfaces/src/main/java/com/agentcore/interfaces/web/ModelController.java)、[FileModelCatalog](../agentcore-spring-boot-starter/src/main/java/com/agentcore/spring/web/FileModelCatalog.java)：管理权限和运行时快照；配置的校验在 `ModelProfileParser`，私有文件的安全读写在 `PrivateSettingsFile`，出错时去掉凭据的包装在 `CredentialSafeGateway`。
- [conversation.js](../agentcore-interfaces/src/main/resources/agentcore-ui/js/conversation.js)、[components.js](../agentcore-interfaces/src/main/resources/agentcore-ui/js/components.js)：历史/实时事件与卡片组件。
- [markdown.js](../agentcore-interfaces/src/main/resources/agentcore-ui/js/markdown.js)：安全的 Markdown 子集。

## 验证

```bash
# 前端模块回归（只用 Node 内置测试库；不安装 npm 依赖）
node --test agentcore-interfaces/src/test/js/*.test.mjs
# Java 集成与全量测试
mvn verify
```

浏览器联调可以使用本地假 OpenAI 服务，避免调用付费模型：

```bash
python3 agentcore-interfaces/src/test/js/fake-model.py --port 18989
# 另一个终端：隔离工作目录与用户配置，不持久化测试会话，只启用 bash
mkdir -p /tmp/agentcore-ui-test/home /tmp/agentcore-ui-test/workspace
java -Duser.home=/tmp/agentcore-ui-test/home -jar agentcore-bootstrap/target/agentcore.jar serve --port 18888 \
  --cwd /tmp/agentcore-ui-test/workspace \
  --agentcore.model=fixture:ui-test \
  --agentcore.providers.fixture.type=openai \
  --agentcore.providers.fixture.base-url=http://127.0.0.1:18989/v1 \
  --agentcore.providers.fixture.api-key=local-test \
  --agentcore.tools=bash --agentcore.sessions.enabled=false \
  --agentcore.memory.enabled=false --agentcore.skills.enabled=false
```

打开 <http://127.0.0.1:18888/>：普通消息检查 Markdown；包含“审批”的消息请求执行无副作用的 `printf`；包含“慢速”的消息用于测试切换会话、取消和插话。动态模型验证可在页面新建两个 `openai` 配置，均指向 `http://127.0.0.1:18989/v1`、使用测试密钥，并分别填写不同模型名称；发送包含“模型”的消息，假服务会回显实际收到的模型名称。此假模型只服务本机，不替代真实厂商兼容性测试。
