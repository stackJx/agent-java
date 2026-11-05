package com.agentcore.domain.model;

import java.util.Map;

/** 发给模型的工具定义（厂商无关）。inputSchema 为 JSON Schema 对象。 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
}
