package com.agentcore.interfaces.cli;

import com.agentcore.domain.permission.Approval;
import com.agentcore.domain.permission.Approver;
import com.agentcore.domain.permission.PermissionRequest;
import com.agentcore.domain.shared.Texts;
import java.util.List;

/** 命令行审批：[y]允许 / [a]总是允许 / [n]拒绝（也可直接输入拒绝理由，作为反馈交给模型）。 */
public final class ConsoleApprover implements Approver {

    private final ConsoleIO io;
    private final Ansi a;

    public ConsoleApprover(ConsoleIO io, Ansi ansi) {
        this.io = io;
        this.a = ansi;
    }

    @Override
    public Approval approve(PermissionRequest req) {
        io.err().println(a.yellow("\n? 允许执行 " + Texts.clip(req.summary(), 300)));
        if (req.tool().equals("write_file") || req.tool().equals("edit_file")) {
            Object preview = req.arguments().getOrDefault("new_string", req.arguments().get("content"));
            if (preview != null) {
                List<String> lines = String.valueOf(preview).lines().toList();
                StringBuilder sb = new StringBuilder();
                lines.stream().limit(12).forEach(l -> sb.append("    ").append(l).append('\n'));
                if (lines.size() > 12) {
                    sb.append("    … (+").append(lines.size() - 12).append(" lines)\n");
                }
                io.err().print(a.dim(sb.toString()));
            }
        }
        String ans = io.readLine("  [y]允许 / [a]总是允许 / [n]拒绝（也可直接输入拒绝理由）: ");
        if (ans == null) {
            return Approval.deny("no input available");
        }
        String s = ans.strip();
        return switch (s.toLowerCase()) {
            case "", "y", "yes", "是" -> Approval.allow();
            case "a", "always", "总是" -> Approval.always();
            case "n", "no", "否" -> Approval.deny("");
            default -> Approval.deny("user denied with feedback: " + s);
        };
    }
}
