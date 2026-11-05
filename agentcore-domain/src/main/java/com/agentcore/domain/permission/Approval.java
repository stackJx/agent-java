package com.agentcore.domain.permission;

/**
 * 审批结果。
 *
 * @param remember 为 true 时本会话内记住（等同「总是允许」）
 * @param message  拒绝时给模型的反馈，例如「不要删文件，改用 git stash」
 */
public record Approval(Decision decision, boolean remember, String message) {

    public static Approval allow() {
        return new Approval(Decision.ALLOW, false, "");
    }

    public static Approval always() {
        return new Approval(Decision.ALLOW, true, "");
    }

    public static Approval deny(String message) {
        return new Approval(Decision.DENY, false, message == null ? "" : message);
    }
}
