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
        String desc = spec.path("description").asText(spec.path("title").asText(remoteName));
        this.description = "[MCP server: " + client.name() + "] " + desc;
        Map<String, Object> s = new LinkedHashMap<>(Json.toMap(spec.path("inputSchema")));
        s.putIfAbsent("type", "object");
        this.schema = s;
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
        JsonNode res;
        try {
            res = client.callTool(remoteName, arguments);
        } catch (McpException e) {
            throw new ToolException(e.getMessage());
        }
        List<ContentBlock> blocks = convert(res.path("content"));
        if (blocks.isEmpty() && res.hasNonNull("structuredContent")) {
            blocks.add(new TextBlock(Json.pretty(res.get("structuredContent"))));
        }
        if (blocks.isEmpty()) {
            blocks.add(new TextBlock("(no output)"));
        }
        return new ToolOutput(blocks, res.path("isError").asBoolean(false), Map.of());
    }

    static List<ContentBlock> convert(JsonNode items) {
        List<ContentBlock> out = new ArrayList<>();
        for (JsonNode it : items) {
            switch (it.path("type").asText()) {
                case "text" -> out.add(new TextBlock(it.path("text").asText("")));
                case "image" -> out.add(ImageBlock.base64(it.path("mimeType").asText("image/png"), it.path("data").asText("")));
                case "resource" -> {
                    JsonNode r = it.path("resource");
                    out.add(new TextBlock(r.hasNonNull("text") ? r.get("text").asText()
                            : "(binary resource " + r.path("uri").asText() + ", " + r.path("mimeType").asText() + ")"));
                }
                case "resource_link" -> out.add(new TextBlock(("Resource link: " + it.path("uri").asText() + " "
                        + it.path("name").asText()).strip()));
                case "audio" -> out.add(new TextBlock("(audio content omitted)"));
                default -> out.add(new TextBlock(Json.write(it)));
            }
        }
        return out;
    }
}
