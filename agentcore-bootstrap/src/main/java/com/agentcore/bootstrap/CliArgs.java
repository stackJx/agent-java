package com.agentcore.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行参数。{@link #parse} 认出命令和 agentcore 自己的选项；{@link #toSpringArgs} 把它们翻译成 Spring 配置项。
 * 以 {@code --} 开头又不认识的参数原样交给 Spring（例如 {@code --agentcore.web.token=xxx}）。
 */
final class CliArgs {

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

    static CliArgs parse(String[] argv) {
        CliArgs args = new CliArgs();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            switch (arg) {
                case "-m", "--model" -> args.model = argv[++i];
                case "--config" -> args.config = argv[++i];
                case "--cwd" -> args.cwd = argv[++i];
                case "--resume" -> args.resume = argv[++i];
                case "-c", "--continue" -> args.continueLast = true;
                case "--yolo" -> args.yolo = true;
                case "--readonly" -> args.readonly = true;
                case "--no-session" -> args.noSession = true;
                case "--hide-thinking" -> args.hideThinking = true;
                case "--events" -> args.events = true;
                case "--port" -> args.port = Integer.parseInt(argv[++i]);
                case "--host" -> args.host = argv[++i];
                case "-h", "--help" -> args.command = "help";
                case "chat", "run", "serve", "sessions", "init", "help" -> {
                    // 只有第一个出现、且在任务描述之前的才算命令；"agentcore run 帮我 run 一下测试" 里第二个 run 是任务的一部分
                    if (args.rest.isEmpty() && args.command.equals("chat")) {
                        args.command = arg;
                    } else {
                        args.rest.add(arg);
                    }
                }
                default -> (arg.startsWith("--") ? args.spring : args.rest).add(arg);
            }
        }
        return args;
    }

    Path cwdPath() {
        return Path.of(cwd == null ? "." : cwd).toAbsolutePath().normalize();
    }

    /** 命令行选项 → Spring 配置项。以命令行参数的形式传入，优先级高于所有配置文件。 */
    List<String> toSpringArgs() {
        List<String> springArgs = new ArrayList<>();
        springArgs.add("--agentcore.agent.working-directory=" + cwdPath());
        if (model != null) {
            springArgs.add("--agentcore.model=" + model);
        }
        if (yolo) {
            springArgs.add("--agentcore.permissions.mode=auto");
        }
        if (readonly) {
            springArgs.add("--agentcore.permissions.mode=readonly");
        }
        if (noSession) {
            springArgs.add("--agentcore.sessions.enabled=false");
        }
        if (port != null) {
            springArgs.add("--server.port=" + port);
        }
        if (host != null) {
            springArgs.add("--server.address=" + host);
        }
        if (config != null) {
            Path file = Path.of(config).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("配置文件不存在：" + file);
            }
            springArgs.add("--spring.config.additional-location=file:" + file);
        }
        springArgs.addAll(spring);
        return springArgs;
    }
}
