package com.agentcore.domain.permission;

public enum PermissionMode {
    /** 按规则 + 工具默认值（只读放行、其余询问）。 */
    DEFAULT,
    /** 除 deny 规则外全部放行（无人值守 / 沙箱环境）。 */
    AUTO,
    /** 只允许默认放行的工具，其余一律拒绝。 */
    READONLY;

    public static PermissionMode parse(String s) {
        if (s == null || s.isBlank()) {
            return DEFAULT;
        }
        return switch (s.strip().toLowerCase()) {
            case "auto", "yolo", "bypass" -> AUTO;
            case "readonly", "read_only", "read-only" -> READONLY;
            default -> DEFAULT;
        };
    }
}
