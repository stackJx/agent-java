package com.agentcore.interfaces.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 共享的控制台输入输出（REPL、审批、提问共用一个 reader，避免抢输入）。 */
public final class ConsoleIO {

    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;

    public ConsoleIO(InputStream in, PrintStream out, PrintStream err) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
        this.err = err;
    }

    public static ConsoleIO system() {
        return new ConsoleIO(System.in, new PrintStream(System.out, true, StandardCharsets.UTF_8),
                new PrintStream(System.err, true, StandardCharsets.UTF_8));
    }

    public PrintStream out() {
        return out;
    }

    public PrintStream err() {
        return err;
    }

    /** 读一行；EOF 返回 null。 */
    public synchronized String readLine(String prompt) {
        err.print(prompt);
        err.flush();
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
