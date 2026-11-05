package com.agentcore.domain.llm.model;

public enum StopReason {
    /** 模型自然结束。 */
    END_TURN,
    /** 模型要调用工具。 */
    TOOL_USE,
    /** 单次输出达到上限。 */
    MAX_TOKENS,
    /** 服务端工具暂停，需原样续发。 */
    PAUSE_TURN,
    /** 安全拒答。 */
    REFUSAL,
    /** 上下文超窗。 */
    CONTEXT_OVERFLOW,
    /** Agent 层：达到最大轮数。 */
    MAX_TURNS,
    /** Agent 层：被取消。 */
    CANCELLED,
    /** Agent 层：不可恢复错误。 */
    ERROR;

    public String wire() {
        return name().toLowerCase();
    }
}
