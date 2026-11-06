package com.agentcore.interfaces.cli;

import com.agentcore.domain.tool.gateway.UserPrompter;
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
        String input;
        try {
            input = io.readLine("> ");
        } catch (ConsoleIO.InterruptedInput e) {
            return "(no answer)";
        }
        if (input == null || input.isBlank()) {
            return "(no answer)";
        }
        String answer = input.strip();
        // 输入序号就当作选了对应的选项
        if (answer.matches("\\d+")) {
            int choice = Integer.parseInt(answer);
            if (choice >= 1 && choice <= options.size()) {
                return options.get(choice - 1);
            }
        }
        return answer;
    }
}
