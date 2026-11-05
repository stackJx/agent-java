package com.agentcore.interfaces.cli;

import com.agentcore.domain.tool.UserPrompter;
import java.util.List;

/** 命令行提问（ask_user 工具）。 */
public final class ConsolePrompter implements UserPrompter {

    private final ConsoleIO io;

    public ConsolePrompter(ConsoleIO io) {
        this.io = io;
    }

    @Override
    public String ask(String question, List<String> options) {
        io.err().println("\n? " + question);
        for (int i = 0; i < options.size(); i++) {
            io.err().println("  " + (i + 1) + ". " + options.get(i));
        }
        String ans = io.readLine("> ");
        if (ans == null || ans.isBlank()) {
            return "(no answer)";
        }
        String s = ans.strip();
        if (s.matches("\\d+")) {
            int idx = Integer.parseInt(s);
            if (idx >= 1 && idx <= options.size()) {
                return options.get(idx - 1);
            }
        }
        return s;
    }
}
