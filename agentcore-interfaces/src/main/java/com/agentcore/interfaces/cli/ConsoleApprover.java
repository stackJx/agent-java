package com.agentcore.interfaces.cli;

import com.agentcore.domain.permission.gateway.Approver;
import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.model.PermissionRequest;
import com.agentcore.domain.shared.util.Texts;
import java.util.List;

/** 命令行审批：[y]允许 / [a]总是允许 / [n]拒绝（也可直接输入拒绝理由，作为反馈交给模型）。 */
public final class ConsoleApprover implements Approver {

    private final ConsoleIO io;
    private final Ansi ansi;

    private static final int PREVIEW_LINES = 12;

    public ConsoleApprover(ConsoleIO io, Ansi ansi) {
        this.io = io;
        this.ansi = ansi;
    }

    @Override
    public Approval approve(PermissionRequest request) {
        io.err().println(ansi.yellow("\n? 允许执行 " + Texts.clip(request.summary(), 300)));
        if (request.tool().equals("write_file") || request.tool().equals("edit_file")) {
            printPreview(request.arguments().getOrDefault("new_string", request.arguments().get("content")));
        }
        String input;
        try {
            input = io.readLine("  [y]允许 / [a]总是允许 / [n]拒绝（也可直接输入拒绝理由）: ");
        } catch (ConsoleIO.InterruptedInput e) {
            return Approval.deny("cancelled by user"); // 在审批提示下按 Ctrl+C 当作拒绝，绝不能当作允许
        }
        if (input == null) {
            return Approval.deny("no input available");
        }
        String answer = input.strip();
        return switch (answer.toLowerCase()) {
            case "", "y", "yes", "是" -> Approval.allow();
            case "a", "always", "总是" -> Approval.always();
            case "n", "no", "否" -> Approval.deny("");
            default -> Approval.deny("user denied with feedback: " + answer); // 其他输入当作拒绝理由交给模型
        };
    }

    /** 写文件前给用户看一眼要写的内容（前几行）。 */
    private void printPreview(Object content) {
        if (content == null) {
            return;
        }
        List<String> lines = String.valueOf(content).lines().toList();
        StringBuilder preview = new StringBuilder();
        lines.stream().limit(PREVIEW_LINES).forEach(line -> preview.append("    ").append(line).append('\n'));
        if (lines.size() > PREVIEW_LINES) {
            preview.append("    … (+").append(lines.size() - PREVIEW_LINES).append(" lines)\n");
        }
        io.err().print(ansi.dim(preview.toString()));
    }
}
