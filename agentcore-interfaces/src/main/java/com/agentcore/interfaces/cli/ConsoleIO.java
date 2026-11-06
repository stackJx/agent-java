package com.agentcore.interfaces.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * 共享的控制台输入输出（REPL、审批、提问共用一个读取器，避免抢输入）。
 *
 * <ul>
 *   <li>真实终端：用 JLine 读行——方向键编辑、上下翻历史（存在 ~/.agentcore/history）、Tab 补全斜杠命令；
 *       Ctrl+C 交给 {@link #onInterrupt} 注册的动作（运行中取消当前任务）；</li>
 *   <li>管道 / 重定向 / 测试：退回按行读取，行为和以前一样。</li>
 * </ul>
 */
public final class ConsoleIO {

    /** 在提示符下按了 Ctrl+C：调用方决定是清空这一行、拒绝审批还是放弃回答。 */
    public static final class InterruptedInput extends RuntimeException {
        InterruptedInput() {
            super("input interrupted", null, false, false);
        }
    }

    /** {@link #onInterrupt} 的返回值：关闭时恢复 Ctrl+C 原来的处理方式。 */
    @FunctionalInterface
    public interface InterruptHandler extends AutoCloseable {
        @Override
        void close();
    }

    /** Tab 补全用的斜杠命令。 */
    static final List<String> COMMANDS = List.of("/help", "/new", "/clear", "/compact", "/tools", "/usage", "/sessions",
            "/resume", "/memory", "/exit", "/quit");

    private final PrintStream out;
    private final PrintStream err;
    private final BufferedReader plainInput;
    private final Terminal terminal;
    private final LineReader lineReader;

    /** 非交互模式：从给定的流按行读取（管道、测试）。 */
    public ConsoleIO(InputStream in, PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
        this.plainInput = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.terminal = null;
        this.lineReader = null;
    }

    private ConsoleIO(Terminal terminal, LineReader lineReader, PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
        this.plainInput = null;
        this.terminal = terminal;
        this.lineReader = lineReader;
    }

    /** 标准输入输出都是终端时用 JLine，否则（被管道或重定向）按行读取。 */
    public static ConsoleIO system() {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        if (System.console() == null) {
            return new ConsoleIO(System.in, out, err);
        }
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            return new ConsoleIO(terminal, lineReader(terminal), out, err);
        } catch (IOException | RuntimeException e) {
            return new ConsoleIO(System.in, out, err); // 终端初始化失败就退回最简单的方式
        }
    }

    private static LineReader lineReader(Terminal terminal) {
        // 不把引号、反斜杠当特殊字符：输入 don't 不会等着补全引号；行尾的 \ 续行由 ChatRepl 自己处理
        DefaultParser parser = new DefaultParser();
        parser.setQuoteChars(new char[0]);
        parser.setEscapeChars(new char[0]);
        return LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("agentcore")
                .parser(parser)
                .completer(new StringsCompleter(COMMANDS))
                .variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".agentcore", "history"))
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true) // 输入里的 ! 不当作历史展开
                .build();
    }

    public PrintStream out() {
        return out;
    }

    public PrintStream err() {
        return err;
    }

    /** 读一行；输入结束（Ctrl+D / EOF）返回 null，在提示符下按 Ctrl+C 抛 {@link InterruptedInput}。 */
    public synchronized String readLine(String prompt) {
        if (lineReader == null) {
            err.print(prompt);
            err.flush();
            try {
                return plainInput.readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        try {
            return lineReader.readLine(prompt);
        } catch (UserInterruptException e) {
            throw new InterruptedInput();
        } catch (EndOfFileException e) {
            return null;
        }
    }

    /**
     * 运行期间按 Ctrl+C 执行 action（例如取消当前任务）而不是退出程序。返回值关闭时恢复原来的处理方式。
     * 非交互模式下不拦截。
     */
    public InterruptHandler onInterrupt(Runnable action) {
        if (terminal == null) {
            return () -> { };
        }
        Terminal.SignalHandler previous = terminal.handle(Terminal.Signal.INT, signal -> action.run());
        return () -> terminal.handle(Terminal.Signal.INT, previous);
    }
}
