package com.agentcore.bootstrap;

import com.agentcore.application.AgentRuntime;
import com.agentcore.application.AgentService;
import com.agentcore.application.approval.AsyncApprover;
import com.agentcore.domain.agent.RunResult;
import com.agentcore.domain.conversation.ConversationSummary;
import com.agentcore.domain.model.StopReason;
import com.agentcore.infrastructure.config.Settings;
import com.agentcore.infrastructure.json.EventCodec;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.interfaces.cli.Ansi;
import com.agentcore.interfaces.cli.ChatRepl;
import com.agentcore.interfaces.cli.ConsoleApprover;
import com.agentcore.interfaces.cli.ConsoleIO;
import com.agentcore.interfaces.cli.ConsolePrompter;
import com.agentcore.interfaces.cli.ConsoleRenderer;
import com.agentcore.interfaces.http.AgentHttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 命令行入口。
 * <pre>
 * agentcore                         交互式对话
 * agentcore run "任务描述"           一次性执行（--events 输出 JSONL 事件）
 * agentcore serve --port 8080       HTTP + SSE 服务
 * agentcore sessions                列出历史会话
 * agentcore init                    生成 agentcore.yaml 模板
 * 通用参数：-m 模型 --config 文件 --cwd 目录 --resume ID -c(继续上次) --yolo --readonly --no-session --hide-thinking
 * </pre>
 */
public final class AgentCoreApplication {

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
        int port = -1;
        String host = "127.0.0.1";
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
                    default -> a.rest.add(s);
                }
            }
            return a;
        }
    }

    private static final String USAGE = """
            agentcore —— 通用 Agent 底座（Java / DDD）

            用法：
              agentcore [chat]                 交互式对话
              agentcore run "任务描述"          一次性执行（省略任务则从 stdin 读取；--events 输出 JSONL 事件）
              agentcore serve [--port 8080]    启动 HTTP + SSE 服务
              agentcore sessions               列出历史会话
              agentcore init                   在当前目录生成 agentcore.yaml 模板

            参数：
              -m, --model <p:m>   模型，例如 deepseek:deepseek-v4-pro / anthropic:claude-opus-5-5 / mock
              --config <file>     配置文件
              --cwd <dir>         工作目录
              --resume <id>       恢复指定会话；-c 继续最近一次会话
              --yolo              跳过所有审批（危险）；--readonly 只读模式
              --no-session        不保存会话；--hide-thinking 不显示思考过程
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
                case "init" -> init(a);
                case "sessions" -> sessions(a);
                case "run" -> runOnce(a);
                case "serve" -> serve(a);
                default -> chat(a);
            };
        } catch (IllegalArgumentException e) {
            System.err.println("错误：" + e.getMessage());
            return 2;
        }
    }

    private static Path cwd(Args a) {
        return Path.of(a.cwd == null ? "." : a.cwd).toAbsolutePath().normalize();
    }

    static Settings settings(Args a) {
        Settings s = Settings.load(a.config == null ? null : Path.of(a.config), cwd(a));
        if (a.model != null) {
            s.set("model", a.model);
        }
        if (a.yolo) {
            s.set("permissions.mode", "auto");
        }
        if (a.readonly) {
            s.set("permissions.mode", "readonly");
        }
        if (a.noSession) {
            s.set("sessions.enabled", false);
        }
        return s;
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

    private static int chat(Args a) {
        ConsoleIO io = ConsoleIO.system();
        Ansi ansi = Ansi.auto();
        try (AgentRuntime rt = RuntimeAssembler.assemble(settings(a), RuntimeAssembler.Options.of(cwd(a))
                .withApprover(new ConsoleApprover(io, ansi)).withPrompter(new ConsolePrompter(io)))) {
            return new ChatRepl(rt, io, ansi, !a.hideThinking, conversation(rt.service(), a)).run();
        }
    }

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
        try (AgentRuntime rt = RuntimeAssembler.assemble(settings(a), RuntimeAssembler.Options.of(cwd(a)))) {
            String id = conversation(rt.service(), a);
            RunResult r;
            if (a.events) {
                r = rt.service().chat(id, prompt, e -> io.out().println(Json.write(EventCodec.encode(e))));
            } else {
                r = rt.service().chat(id, prompt, new ConsoleRenderer(io.out(), io.err(), Ansi.auto(), !a.hideThinking));
                io.out().println();
            }
            return r.stopReason() == StopReason.END_TURN && r.error() == null ? 0 : 1;
        }
    }

    private static int serve(Args a) {
        Settings s = settings(a);
        int port = a.port > 0 ? a.port : s.integer("server.port", 8080);
        AsyncApprover approver = new AsyncApprover(Duration.ofSeconds(s.integer("server.approval_timeout_seconds", 300)));
        try (AgentRuntime rt = RuntimeAssembler.assemble(s, RuntimeAssembler.Options.of(cwd(a)).withApprover(approver));
             AgentHttpServer server = new AgentHttpServer(rt.service(), approver, a.host, port, s.string("server.token", null)).start()) {
            rt.warnings().forEach((k, v) -> System.err.println(k + "：" + v));
            System.err.println("agentcore HTTP 服务已启动：http://" + a.host + ":" + server.port() + "  (" + rt.engine().model().describe() + ")");
            CountDownLatch latch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));
            latch.await();
            return 0;
        } catch (IOException e) {
            System.err.println("启动失败：" + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private static int sessions(Args a) {
        Settings s = settings(a);
        s.set("mcp_servers", Map.of());
        if (s.string("model", null) == null) {
            s.set("model", "mock");
        }
        try (AgentRuntime rt = RuntimeAssembler.assemble(s, RuntimeAssembler.Options.of(cwd(a)))) {
            for (ConversationSummary c : rt.service().list(50)) {
                System.out.printf("%s  %4d msgs  %s%n", c.id(), c.messageCount(), c.title());
            }
        }
        return 0;
    }

    static final String SAMPLE_CONFIG = """
            # agentcore 配置。字符串里可以用 ${ENV_VAR} 引用环境变量。
            model: deepseek:deepseek-v4-pro          # 或 anthropic:claude-opus-5-5 / qwen:qwen-max / ollama:qwen3 ...

            providers:
              deepseek:
                api_key_env: DEEPSEEK_API_KEY
                # reasoning_effort: high
              anthropic:
                effort: high                         # low / medium / high / xhigh / max
              # openai:                              # openai:<模型> 默认走 Responses API（/responses）
              #   reasoning_effort: medium
              #   reasoning_summary: auto            # 显示思考摘要（部分组织需先完成验证）
              # 自定义 OpenAI 兼容服务：model 写成 myvllm:<模型名>
              # type 可选 openai（Chat Completions）或 openai-responses（Responses）
              # myvllm:
              #   type: openai
              #   base_url: http://localhost:8000/v1
              #   api_key: EMPTY
              #   context_window: 131072

            agent:
              name: agentcore
              max_turns: 100
              instructions: |
                （可选）对 Agent 的额外要求，例如：回答使用简体中文。

            # tools: [read_file, write_file, edit_file, glob, grep, bash, web_fetch, todo_write, ask_user, task]

            permissions:
              mode: default                          # default / auto（全部放行）/ readonly
              allow: ["bash(git status*)", "bash(git diff*)", "bash(ls *)"]
              deny: ["bash(rm -rf *)", "bash(sudo *)"]

            # mcp_servers:
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

            # 会话与长期记忆存数据库（默认存 ~/.agentcore 下的文件）。JDBC 驱动放到 AGENTCORE_CLASSPATH 里。
            # persistence:
            #   jdbc:
            #     url: jdbc:mysql://localhost:3306/agent?useUnicode=true&characterEncoding=utf8
            #     username: agent
            #     password: ${DB_PASSWORD}
            #     platform: auto                     # auto / mysql / postgresql / oracle / sqlserver / h2
            #     initialize_schema: create-if-missing   # never：由 DBA 执行建表脚本

            server:
              port: 8080
              # token: ${AGENTCORE_TOKEN}            # 设置后 /api 需要 Authorization: Bearer <token>
            """;

    private static int init(Args a) {
        Path p = cwd(a).resolve("agentcore.yaml");
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
