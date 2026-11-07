package com.agentcore.bootstrap;

import com.agentcore.application.AgentService;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationSummary;
import com.agentcore.domain.conversation.repository.ConversationRepository;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.memory.repository.MemoryRepository;
import com.agentcore.domain.permission.gateway.Approver;
import com.agentcore.domain.tool.gateway.UserPrompter;
import com.agentcore.infrastructure.json.EventCodec;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.mcp.McpServerManager;
import com.agentcore.interfaces.cli.Ansi;
import com.agentcore.interfaces.cli.ChatRepl;
import com.agentcore.interfaces.cli.ConsoleApprover;
import com.agentcore.interfaces.cli.ConsoleIO;
import com.agentcore.interfaces.cli.ConsolePrompter;
import com.agentcore.interfaces.cli.ConsoleRenderer;
import com.agentcore.spring.AgentCoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;

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
    /** agentcore init 生成的配置模板。 */
    static final String CONFIG_TEMPLATE = "/agentcore-template.yaml";

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

    /** ① 解析参数 → 按命令分发。返回进程退出码。 */
    public static int run(String[] argv) {
        CliArgs args;
        try {
            args = CliArgs.parse(argv);
        } catch (RuntimeException e) {
            System.err.println("参数错误：" + e.getMessage() + "\n\n" + USAGE);
            return 2;
        }
        try {
            return switch (args.command) {
                case "help" -> {
                    System.out.println(USAGE);
                    yield 0;
                }
                case "init" -> init(args);       // help / init 不需要启动 Spring
                case "sessions" -> sessions(args);
                case "run" -> runOnce(args);
                case "serve" -> serve(args);
                default -> chat(args);
            };
        } catch (RuntimeException e) {
            // 启动失败（没配模型、配置写错……）和命令本身的错误：只打印最根本的原因
            System.err.println("错误：" + rootMessage(e));
            return 2;
        }
    }

    // ------------------------------------------------------------------ ② 启动 Spring 容器

    /**
     * @param web        true 时启动内嵌 Web 服务（serve），否则只建容器不监听端口
     * @param registrar  在容器刷新前注册额外的 Bean（例如命令行的审批人）
     * @param extraArgs  额外的 Spring 配置
     */
    static ConfigurableApplicationContext start(CliArgs args, boolean web, Consumer<GenericApplicationContext> registrar,
                                                String... extraArgs) {
        SpringApplication app = new SpringApplication(AgentCoreApplication.class);
        app.setWebApplicationType(web ? WebApplicationType.SERVLET : WebApplicationType.NONE);
        // 只读内置配置（它再导入用户配置），不读当前目录的 application.yml
        app.setDefaultProperties(Map.of("spring.config.location", CLI_CONFIG));
        app.addListeners(new OptionalDataSource());
        if (registrar != null) {
            app.addInitializers(context -> registrar.accept((GenericApplicationContext) context));
        }
        List<String> springArgs = new ArrayList<>(args.toSpringArgs());
        springArgs.addAll(List.of(extraArgs));
        return app.run(springArgs.toArray(String[]::new));
    }

    // ------------------------------------------------------------------ ③ 命令

    private static int chat(CliArgs args) {
        ConsoleIO io = ConsoleIO.system();
        Ansi ansi = Ansi.auto();
        // 交互模式：审批和 ask_user 都在终端里问
        try (ConfigurableApplicationContext context = start(args, false, beans -> {
            beans.registerBean("agentConsoleApprover", Approver.class, () -> new ConsoleApprover(io, ansi));
            beans.registerBean("agentConsolePrompter", UserPrompter.class, () -> new ConsolePrompter(io));
        })) {
            AgentService service = context.getBean(AgentService.class);
            MemoryRepository memory = context.getBeanProvider(MemoryRepository.class).getIfAvailable();
            return new ChatRepl(service, memory, warnings(context), io, ansi, !args.hideThinking, conversation(service, args)).run();
        }
    }

    /** 一次性执行。非交互：没有审批人，需要询问的操作一律拒绝（要放行就加 --yolo 或配置 allow 规则）。 */
    private static int runOnce(CliArgs args) {
        ConsoleIO io = ConsoleIO.system();
        String prompt = taskFrom(args);
        try (ConfigurableApplicationContext context = start(args, false, null)) {
            AgentService service = context.getBean(AgentService.class);
            warnings(context).forEach((source, problem) -> io.err().println(source + "：" + problem));
            String id = conversation(service, args);
            RunResult result;
            if (args.events) {
                result = service.chat(id, prompt, event -> io.out().println(Json.write(EventCodec.encode(event))));
            } else {
                result = service.chat(id, prompt, new ConsoleRenderer(io.out(), io.err(), Ansi.auto(), !args.hideThinking));
                io.out().println();
            }
            return result.stopReason() == StopReason.END_TURN && result.error() == null ? 0 : 1;
        }
    }

    /** 任务描述：命令行上的剩余参数；没有就从 stdin 读（方便 echo "..." | agentcore run）。 */
    private static String taskFrom(CliArgs args) {
        String prompt = String.join(" ", args.rest).strip();
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
        return prompt;
    }

    /** HTTP + SSE 服务：控制器、Web 审批、token 鉴权都来自 starter 的 Web 自动装配。阻塞到进程收到停止信号。 */
    private static int serve(CliArgs args) {
        CountDownLatch closed = new CountDownLatch(1);
        ConfigurableApplicationContext context = start(args, true, beans -> beans.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent) {
                closed.countDown();
            }
        }));
        warnings(context).forEach((source, problem) -> System.err.println(source + "：" + problem));
        Environment env = context.getEnvironment();
        AgentService service = context.getBean(AgentService.class);
        String origin = "http://" + env.getProperty("server.address", "0.0.0.0") + ":" + env.getProperty("local.server.port")
                + env.getProperty("server.servlet.context-path", "").replaceAll("/+$", "");
        AgentCoreProperties.Web web = context.getBean(AgentCoreProperties.class).getWeb();
        System.err.println("agentcore HTTP 服务已启动（" + service.engine().model().describe() + "）");
        if (web.isEnabled()) {
            if (web.getUi().isEnabled()) {
                System.err.println("  页面：" + origin + web.getUi().getPath() + "/");
            }
            System.err.println("  接口：" + origin + web.getBasePath());
        }
        try {
            closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    /** 列出会话：只需要仓储，用懒加载启动，不创建模型、不启动 MCP 服务器。 */
    private static int sessions(CliArgs args) {
        try (ConfigurableApplicationContext context = start(args, false, null, "--spring.main.lazy-initialization=true")) {
            for (ConversationSummary summary : context.getBean(ConversationRepository.class).list(50)) {
                System.out.printf("%s  %4d msgs  %s%n", summary.id(), summary.messageCount(), summary.title());
            }
        }
        return 0;
    }

    /** 在当前目录写一份 agentcore.yaml 模板（已存在就不覆盖）。 */
    private static int init(CliArgs args) {
        Path file = args.cwdPath().resolve("agentcore.yaml");
        if (Files.exists(file)) {
            System.err.println(file + " 已存在");
            return 1;
        }
        try (InputStream template = AgentCoreApplication.class.getResourceAsStream(CONFIG_TEMPLATE)) {
            Files.write(file, template.readAllBytes());
        } catch (IOException e) {
            System.err.println("写入失败：" + e.getMessage());
            return 1;
        }
        System.out.println("已生成 " + file);
        return 0;
    }

    // ------------------------------------------------------------------ 工具方法

    /** --resume 指定的会话，或 -c 最近一次会话，否则新建。 */
    private static String conversation(AgentService service, CliArgs args) {
        if (args.resume != null) {
            return service.find(args.resume).orElseThrow(() -> new IllegalArgumentException("会话不存在：" + args.resume)).id().value();
        }
        if (args.continueLast) {
            Optional<Conversation> latest = service.latest();
            if (latest.isPresent()) {
                return latest.get().id().value();
            }
        }
        return service.startConversation(Map.of()).id().value();
    }

    /** 启动期的非致命问题（目前是没起来的 MCP 服务器），显示给用户。 */
    private static Map<String, String> warnings(ConfigurableApplicationContext context) {
        Map<String, String> warnings = new LinkedHashMap<>();
        context.getBeanProvider(McpServerManager.class).ifAvailable(servers ->
                servers.errors().forEach((server, error) -> warnings.put("MCP 服务器 " + server + " 启动失败", error)));
        return warnings;
    }

    /** 异常链最底层的原因；找不到类时提示怎么加外部 jar。 */
    static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof ClassNotFoundException || root instanceof NoClassDefFoundError) {
            return "找不到类 " + root.getMessage() + "。JDBC 驱动等外部 jar 请通过环境变量 AGENTCORE_CLASSPATH 加入";
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
