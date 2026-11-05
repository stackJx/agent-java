package com.agentcore.bootstrap;

import com.agentcore.application.AgentService;
import com.agentcore.domain.agent.RunResult;
import com.agentcore.domain.conversation.ConversationRepository;
import com.agentcore.domain.conversation.ConversationSummary;
import com.agentcore.domain.memory.MemoryRepository;
import com.agentcore.domain.model.StopReason;
import com.agentcore.domain.permission.Approver;
import com.agentcore.domain.tool.UserPrompter;
import com.agentcore.infrastructure.json.EventCodec;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.mcp.McpServerManager;
import com.agentcore.interfaces.cli.Ansi;
import com.agentcore.interfaces.cli.ChatRepl;
import com.agentcore.interfaces.cli.ConsoleApprover;
import com.agentcore.interfaces.cli.ConsoleIO;
import com.agentcore.interfaces.cli.ConsolePrompter;
import com.agentcore.interfaces.cli.ConsoleRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 独立部署版的入口（{@code bin/agentcore}）。它就是一个普通的 Spring Boot 应用：
 * 模型、工具、仓储、引擎全部由 agentcore-spring-boot-starter 自动装配，这里只负责
 * ① 解析命令行参数并翻译成 Spring 配置 → ② 按命令启动容器（serve 启动 Web，其余不启动 Web）→ ③ 执行命令。
 *
 * <pre>
 * agentcore                         交互式对话
 * agentcore run "任务描述"           一次性执行（--events 输出 JSONL 事件）
 * agentcore serve --port 8080       HTTP + SSE 服务
 * agentcore sessions                列出历史会话
 * agentcore init                    生成 agentcore.yaml 模板
 * </pre>
 */
@SpringBootApplication
public class AgentCoreApplication {

    /** 内置默认配置；用户配置由它导入（见 agentcore-cli.yml）。 */
    static final String CLI_CONFIG = "classpath:/agentcore-cli.yml";
    static final String DATASOURCE_AUTO_CONFIG = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";

    static final class Args {
        String command = "chat";
        String model;
        String config;
        String cwd;
        String resume;
        boolean continueLast;
        boolean yolo;
        boolean readonly;
        boolean noSession;
        boolean hideThinking;
        boolean events;
        Integer port;
        String host;
        /** 原样交给 Spring 的参数：--spring.xxx=...、--agentcore.xxx=...、--debug 等。 */
        final List<String> spring = new ArrayList<>();
        /** 其余参数：run 的任务描述。 */
        final List<String> rest = new ArrayList<>();

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String s = argv[i];
                switch (s) {
                    case "-m", "--model" -> a.model = argv[++i];
                    case "--config" -> a.config = argv[++i];
                    case "--cwd" -> a.cwd = argv[++i];
                    case "--resume" -> a.resume = argv[++i];
                    case "-c", "--continue" -> a.continueLast = true;
                    case "--yolo" -> a.yolo = true;
                    case "--readonly" -> a.readonly = true;
                    case "--no-session" -> a.noSession = true;
                    case "--hide-thinking" -> a.hideThinking = true;
                    case "--events" -> a.events = true;
                    case "--port" -> a.port = Integer.parseInt(argv[++i]);
                    case "--host" -> a.host = argv[++i];
                    case "-h", "--help" -> a.command = "help";
                    case "chat", "run", "serve", "sessions", "init", "help" -> {
                        if (a.rest.isEmpty() && a.command.equals("chat")) {
                            a.command = s;
                        } else {
                            a.rest.add(s);
                        }
                    }
                    default -> (s.startsWith("--") ? a.spring : a.rest).add(s);
                }
            }
            return a;
        }

        Path cwdPath() {
            return Path.of(cwd == null ? "." : cwd).toAbsolutePath().normalize();
        }

        /** ① 命令行选项 → Spring 配置项。以命令行参数的形式传入，优先级高于所有配置文件。 */
        List<String> toSpringArgs() {
            List<String> out = new ArrayList<>();
            out.add("--agentcore.agent.working-directory=" + cwdPath());
            if (model != null) {
                out.add("--agentcore.model=" + model);
            }
            if (yolo) {
                out.add("--agentcore.permissions.mode=auto");
            }
            if (readonly) {
                out.add("--agentcore.permissions.mode=readonly");
            }
            if (noSession) {
                out.add("--agentcore.sessions.enabled=false");
            }
            if (port != null) {
                out.add("--server.port=" + port);
            }
            if (host != null) {
                out.add("--server.address=" + host);
            }
            if (config != null) {
                Path p = Path.of(config).toAbsolutePath().normalize();
                if (!Files.isRegularFile(p)) {
                    throw new IllegalArgumentException("配置文件不存在：" + p);
                }
                out.add("--spring.config.additional-location=file:" + p);
            }
            out.addAll(spring);
            return out;
        }
    }

    private static final String USAGE = """
            agentcore —— 通用 Agent 底座（Java / DDD / Spring Boot）

            用法：
              agentcore [chat]                 交互式对话
              agentcore run "任务描述"          一次性执行（省略任务则从 stdin 读取；--events 输出 JSONL 事件）
              agentcore serve [--port 8080]    启动 HTTP + SSE 服务（接口前缀 /api/agent）
              agentcore sessions               列出历史会话
              agentcore init                   在当前目录生成 agentcore.yaml 模板

            参数：
              -m, --model <p:m>   模型，例如 deepseek:deepseek-v4-pro / anthropic:claude-opus-5-5 / mock
              --config <file>     额外的配置文件（格式同 Spring Boot 的 application.yml）
              --cwd <dir>         工作目录
              --resume <id>       恢复指定会话；-c 继续最近一次会话
              --yolo              跳过所有审批（危险）；--readonly 只读模式
              --no-session        不保存会话；--hide-thinking 不显示思考过程
              --port / --host     serve 的端口和监听地址（默认 127.0.0.1:8080）
              --<配置项>=<值>      任意 Spring 配置，例如 --agentcore.web.token=xxx、--spring.datasource.url=jdbc:sqlite:agent.db
              --debug             输出 Spring 的调试信息（排查启动问题）
            """;

    public static void main(String[] argv) {
        System.exit(run(argv));
    }

    public static int run(String[] argv) {
        Args a;
        try {
            a = Args.parse(argv);
        } catch (RuntimeException e) {
            System.err.println("参数错误：" + e.getMessage() + "\n\n" + USAGE);
            return 2;
        }
        try {
            return switch (a.command) {
                case "help" -> {
                    System.out.println(USAGE);
                    yield 0;
                }
                case "init" -> init(a);       // help / init 不需要启动 Spring
                case "sessions" -> sessions(a);
                case "run" -> runOnce(a);
                case "serve" -> serve(a);
                default -> chat(a);
            };
        } catch (RuntimeException e) {
            // 启动失败（没配模型、配置写错……）和命令本身的错误：只打印最根本的原因
            System.err.println("错误：" + rootMessage(e));
            return 2;
        }
    }

    // ------------------------------------------------------------------ ② 启动 Spring 容器

    /**
     * @param web         true 时启动内嵌 Web 服务（serve），否则只建容器不监听端口
     * @param registrar   在容器刷新前注册额外的 Bean（例如命令行的审批人）
     * @param extraArgs   额外的 Spring 配置
     */
    static ConfigurableApplicationContext start(Args a, boolean web, Consumer<GenericApplicationContext> registrar, String... extraArgs) {
        SpringApplication app = new SpringApplication(AgentCoreApplication.class);
        app.setWebApplicationType(web ? WebApplicationType.SERVLET : WebApplicationType.NONE);
        // 只读内置配置（它再导入用户配置），不读当前目录的 application.yml
        app.setDefaultProperties(Map.of("spring.config.location", CLI_CONFIG));
        app.addListeners(new OptionalDataSource());
        if (registrar != null) {
            app.addInitializers(ctx -> registrar.accept((GenericApplicationContext) ctx));
        }
        List<String> args = new ArrayList<>(a.toSpringArgs());
        args.addAll(List.of(extraArgs));
        return app.run(args.toArray(String[]::new));
    }

    /**
     * 没配置 spring.datasource.url 时排除数据源自动装配：Spring Boot 在 classpath 上有 JDBC 却没有地址时会启动失败，
     * 而命令行默认用文件存储，不需要数据库。
     */
    static final class OptionalDataSource implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            ConfigurableEnvironment env = event.getEnvironment();
            if (env.containsProperty("spring.datasource.url")) {
                return;
            }
            String existing = env.getProperty("spring.autoconfigure.exclude", "");
            String value = existing.isBlank() ? DATASOURCE_AUTO_CONFIG : existing + "," + DATASOURCE_AUTO_CONFIG;
            env.getPropertySources().addFirst(new MapPropertySource("agentcoreOptionalDataSource",
                    Map.of("spring.autoconfigure.exclude", value)));
        }
    }

    // ------------------------------------------------------------------ ③ 命令

    private static int chat(Args a) {
        ConsoleIO io = ConsoleIO.system();
        Ansi ansi = Ansi.auto();
        // 交互模式：审批和 ask_user 都在终端里问
        try (ConfigurableApplicationContext ctx = start(a, false, c -> {
            c.registerBean("agentConsoleApprover", Approver.class, () -> new ConsoleApprover(io, ansi));
            c.registerBean("agentConsolePrompter", UserPrompter.class, () -> new ConsolePrompter(io));
        })) {
            AgentService service = ctx.getBean(AgentService.class);
            MemoryRepository memory = ctx.getBeanProvider(MemoryRepository.class).getIfAvailable();
            return new ChatRepl(service, memory, warnings(ctx), io, ansi, !a.hideThinking, conversation(service, a)).run();
        }
    }

    /** 一次性执行。非交互：没有审批人，需要询问的操作一律拒绝（要放行就加 --yolo 或配置 allow 规则）。 */
    private static int runOnce(Args a) {
        ConsoleIO io = ConsoleIO.system();
        String prompt = String.join(" ", a.rest).strip();
        if (prompt.isEmpty()) {
            try {
                prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                prompt = "";
            }
        }
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException("请提供任务描述");
        }
        try (ConfigurableApplicationContext ctx = start(a, false, null)) {
            AgentService service = ctx.getBean(AgentService.class);
            warnings(ctx).forEach((k, v) -> io.err().println(k + "：" + v));
            String id = conversation(service, a);
            RunResult r;
            if (a.events) {
                r = service.chat(id, prompt, e -> io.out().println(Json.write(EventCodec.encode(e))));
            } else {
                r = service.chat(id, prompt, new ConsoleRenderer(io.out(), io.err(), Ansi.auto(), !a.hideThinking));
                io.out().println();
            }
            return r.stopReason() == StopReason.END_TURN && r.error() == null ? 0 : 1;
        }
    }

    /** HTTP + SSE 服务：控制器、Web 审批、token 鉴权都来自 starter 的 Web 自动装配。阻塞到进程收到停止信号。 */
    private static int serve(Args a) {
        CountDownLatch closed = new CountDownLatch(1);
        ConfigurableApplicationContext ctx = start(a, true, c -> c.addApplicationListener(e -> {
            if (e instanceof ContextClosedEvent) {
                closed.countDown();
            }
        }));
        warnings(ctx).forEach((k, v) -> System.err.println(k + "：" + v));
        var env = ctx.getEnvironment();
        AgentService service = ctx.getBean(AgentService.class);
        System.err.println("agentcore HTTP 服务已启动：http://" + env.getProperty("server.address", "0.0.0.0") + ":"
                + env.getProperty("local.server.port") + env.getProperty("agentcore.web.base-path", "/api/agent")
                + "  (" + service.engine().model().describe() + ")");
        try {
            closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    /** 列出会话：只需要仓储，用懒加载启动，不创建模型、不启动 MCP 服务器。 */
    private static int sessions(Args a) {
        try (ConfigurableApplicationContext ctx = start(a, false, null, "--spring.main.lazy-initialization=true")) {
            for (ConversationSummary c : ctx.getBean(ConversationRepository.class).list(50)) {
                System.out.printf("%s  %4d msgs  %s%n", c.id(), c.messageCount(), c.title());
            }
        }
        return 0;
    }

    private static String conversation(AgentService service, Args a) {
        if (a.resume != null) {
            return service.find(a.resume).orElseThrow(() -> new IllegalArgumentException("会话不存在：" + a.resume)).id().value();
        }
        if (a.continueLast) {
            var latest = service.latest();
            if (latest.isPresent()) {
                return latest.get().id().value();
            }
        }
        return service.startConversation(Map.of()).id().value();
    }

    /** 启动期的非致命问题（目前是没起来的 MCP 服务器），显示给用户。 */
    private static Map<String, String> warnings(ConfigurableApplicationContext ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        ctx.getBeanProvider(McpServerManager.class).ifAvailable(m ->
                m.errors().forEach((k, v) -> out.put("MCP 服务器 " + k + " 启动失败", v)));
        return out;
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof ClassNotFoundException || root instanceof NoClassDefFoundError) {
            return "找不到类 " + root.getMessage() + "。JDBC 驱动等外部 jar 请通过环境变量 AGENTCORE_CLASSPATH 加入";
        }
        String msg = root.getMessage();
        return msg == null || msg.isBlank() ? root.getClass().getSimpleName() : msg;
    }

    // ------------------------------------------------------------------ init

    static final String SAMPLE_CONFIG = """
            # agentcore 配置，格式与 Spring Boot 的 application.yml 相同。
            # 读取顺序（后者覆盖前者）：~/.agentcore/config.yaml → ./.agentcore/config.yaml → ./agentcore.yaml → --config 指定的文件。
            # 可以用 ${ENV_VAR} 或 ${ENV_VAR:默认值} 引用环境变量。
            agentcore:
              model: deepseek:deepseek-v4-pro          # 或 anthropic:claude-opus-5-5 / openai:gpt-6-astra / qwen:qwen-max / mock

              providers:
                deepseek:
                  api-key-env: DEEPSEEK_API_KEY
                  # reasoning-effort: high
                anthropic:
                  effort: high                         # low / medium / high / xhigh / max
                # openai:                              # openai:<模型> 默认走 Responses API（/responses）
                #   reasoning-effort: medium
                #   reasoning-summary: auto            # 显示思考摘要（部分组织需先完成验证）
                # 自定义 OpenAI 兼容服务：model 写成 myvllm:<模型名>
                # type 可选 openai（Chat Completions）或 openai-responses（Responses）
                # myvllm:
                #   type: openai
                #   base-url: http://localhost:8000/v1
                #   api-key: EMPTY
                #   context-window: 131072

              agent:
                name: agentcore
                max-turns: 100
                instructions: |
                  （可选）对 Agent 的额外要求，例如：回答使用简体中文。

              # tools: [read_file, write_file, edit_file, glob, grep, bash, web_fetch, todo_write, ask_user, task]

              permissions:
                mode: default                          # default / auto（全部放行）/ readonly
                allow: ["bash(git status*)", "bash(git diff*)", "bash(ls *)"]
                deny: ["bash(rm -rf *)", "bash(sudo *)"]

              # mcp-servers:
              #   filesystem:
              #     command: npx
              #     args: ["-y", "@modelcontextprotocol/server-filesystem", "."]

              subagents:
                researcher:
                  description: 只读调研：搜索代码/网页并给出结论
                  tools: [read_file, glob, grep, web_fetch]
                  # model: deepseek:deepseek-flash     # 子 Agent 可以用更便宜的模型

              context:
                threshold: 0.75                        # 估算 token 超过窗口的 75% 时自动压缩

              # 会话与长期记忆存数据库（默认存 ~/.agentcore 下的文件），同时配置下面的 spring.datasource。
              # JDBC 驱动放到 AGENTCORE_CLASSPATH 里，例如 AGENTCORE_CLASSPATH=/opt/drivers/mysql-connector-j.jar
              # persistence:
              #   jdbc:
              #     enabled: true
              #     initialize-schema: create-if-missing   # never：由 DBA 执行建表脚本

              # web:
              #   token: ${AGENTCORE_TOKEN}          # 设置后接口需要 Authorization: Bearer <token>（health 除外）

            # spring:
            #   datasource:
            #     url: jdbc:mysql://localhost:3306/agent?useUnicode=true&characterEncoding=utf8
            #     username: agent
            #     password: ${DB_PASSWORD}

            server:
              port: 8080
              # address: 0.0.0.0                       # 默认只监听本机
            """;

    private static int init(Args a) {
        Path p = a.cwdPath().resolve("agentcore.yaml");
        if (Files.exists(p)) {
            System.err.println(p + " 已存在");
            return 1;
        }
        try {
            Files.writeString(p, SAMPLE_CONFIG);
        } catch (IOException e) {
            System.err.println("写入失败：" + e.getMessage());
            return 1;
        }
        System.out.println("已生成 " + p);
        return 0;
    }
}
