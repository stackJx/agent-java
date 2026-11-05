package com.agentcore.domain.conversation;

/**
 * 消息内容块。与具体模型厂商无关，由各 ModelGateway 负责与厂商格式互转。
 */
public sealed interface ContentBlock permits TextBlock, ImageBlock, ThinkingBlock, ToolCall, ToolResult {

    /** 块类型（序列化用）。 */
    String type();
}
