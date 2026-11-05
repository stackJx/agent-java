package com.agentcore.infrastructure.mcp;

import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把一个 MCP 远端工具适配成领域 {@link Tool}，名称为 {@code mcp__<server>__<tool>}。 */
public final class McpTool implements Tool {

    private final McpClient client;
    private final String remoteName;
    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final boolean readOnly;

    public McpTool(McpClient client, JsonNode spec) {
        this.client = client;
        this.remoteName = spec.path("name").asText();
        this.name = ("mcp__" + client.name() + "__" + remoteName).replaceAll("[^a-zA-Z0-9_-]", "_");
        String summary = spec.path("description").asText(spec.path("title").asText(remoteName));
        this.description = "[MCP server: " + client.name() + "] " + summary;
        Map<String, Object> inputSchema = new LinkedHashMap<>(Json.toMap(spec.path("inputSchema")));
        inputSchema.putIfAbsent("type", "object");
        this.schema = inputSchema;
        this.readOnly = spec.path("annotations").path("readOnlyHint").asBoolean(false);
    }

    @Override
    public String name() {
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return schema;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public ToolOutput execute(Map<String, Object> arguments, ToolContext context) throws Exception {
        JsonNode result;
        try {
            result = client.callTool(remoteName, arguments);
        } catch (McpException e) {
            throw new ToolException(e.getMessage());
        }
        List<ContentBlock> blocks = convert(result.path("content"));
        if (blocks.isEmpty() && result.hasNonNull("structuredContent")) {
            blocks.add(new TextBlock(Json.pretty(result.get("structuredContent"))));
        }
        if (blocks.isEmpty()) {
            blocks.add(new TextBlock("(no output)"));
        }
        return new ToolOutput(blocks, result.path("isError").asBoolean(false), Map.of());
    }

    /** MCP 的内容条目 → 领域内容块：文字、图片直接对应；资源有文字就放文字；音频等暂不支持的给个占位。 */
    static List<ContentBlock> convert(JsonNode items) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonNode item : items) {
            switch (item.path("type").asText()) {
                case "text" -> blocks.add(new TextBlock(item.path("text").asText("")));
                case "image" -> blocks.add(ImageBlock.base64(item.path("mimeType").asText("image/png"), item.path("data").asText("")));
                case "resource" -> {
                    JsonNode resource = item.path("resource");
                    blocks.add(new TextBlock(resource.hasNonNull("text") ? resource.get("text").asText()
                            : "(binary resource " + resource.path("uri").asText() + ", " + resource.path("mimeType").asText() + ")"));
                }
                case "resource_link" -> blocks.add(new TextBlock(("Resource link: " + item.path("uri").asText() + " "
                        + item.path("name").asText()).strip()));
                case "audio" -> blocks.add(new TextBlock("(audio content omitted)"));
                default -> blocks.add(new TextBlock(Json.write(item)));
            }
        }
        return blocks;
    }
}
