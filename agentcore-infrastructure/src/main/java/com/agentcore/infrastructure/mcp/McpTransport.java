package com.agentcore.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Consumer;

/** MCP 传输层。 */
public interface McpTransport extends AutoCloseable {

    void start(Consumer<JsonNode> onMessage, Runnable onClose) throws Exception;

    void send(JsonNode message) throws Exception;

    /** 初始化后协商出的协议版本（HTTP 传输需要放进请求头）。 */
    default void protocolVersion(String version) {
    }

    default String diagnostics() {
        return "";
    }

    @Override
    void close();
}
