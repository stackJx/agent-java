package com.agentcore.infrastructure.mcp;

import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.PermissionLevel;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.infrastructure.json.Json;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一个 MCP 远端工具适配成领域 {@link Tool}，名称为 {@code mcp__<server>__<tool>}。
 *
 * <p>服务器给工具标的 {@code readOnlyHint} 只是自我声明。MCP 规范要求不要根据不可信服务器的标注做安全决策，
 * 所以默认只用它决定能否并行执行，权限照常询问；配置了 {@code trust_annotations: true} 的服务器，标了只读的工具才免审批。
 */
public final class McpTool implements Tool {

    /** 模型厂商对工具名长度的限制。 */
    static final int MAX_NAME_LENGTH = 64;

    private final McpSyncClient client;
    private final String remoteName;
    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final boolean readOnly;
    private final boolean trustAnnotations;

    /**
     * @param name             工具名（由 {@link McpServerManager} 保证在所有服务器之间唯一）
     * @param trustAnnotations 是否信任服务器的 readOnlyHint（信任时，只读工具免审批）
     */
    public McpTool(String serverName, String name, McpSyncClient client, McpSchema.Tool spec, boolean trustAnnotations) {
        this.client = client;
        this.remoteName = spec.name();
        this.name = name;
        this.trustAnnotations = trustAnnotations;
        String summary = spec.description() != null ? spec.description() : spec.title() != null ? spec.title() : remoteName;
        this.description = "[MCP server: " + serverName + "] " + summary;
        Map<String, Object> inputSchema = new LinkedHashMap<>(spec.inputSchema() == null ? Map.of() : spec.inputSchema());
        inputSchema.putIfAbsent("type", "object");
        this.schema = inputSchema;
        this.readOnly = spec.annotations() != null && Boolean.TRUE.equals(spec.annotations().readOnlyHint());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return schema;
    }

    /** 用 {@code mcp__<server>__<tool>} 拼出的名字，非法字符替换成下划线（还没截断到 {@value #MAX_NAME_LENGTH} 个字符）。 */
    static String nameFor(String serverName, String remoteName) {
        return ("mcp__" + serverName + "__" + remoteName).replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** 服务器声明的只读：决定能否和其他只读工具并行执行。 */
    @Override
    public boolean readOnly() {
        return readOnly;
    }

    /** 只有信任这个服务器的标注时，只读工具才默认放行；否则一律询问。 */
    @Override
    public PermissionLevel defaultPermission() {
        return trustAnnotations && readOnly ? PermissionLevel.ALLOW : PermissionLevel.ASK;
    }

    /** 协议层的错误（服务器断开、JSON-RPC 错误）变成 ToolException 交给模型；工具自己报的错走 isError。 */
    @Override
    public ToolOutput execute(Map<String, Object> arguments, ToolContext context) {
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(remoteName, arguments));
        } catch (McpError e) {
            throw new ToolException("MCP tool " + remoteName + " failed: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new ToolException("MCP tool " + remoteName + " failed: " + McpError.aggregateExceptionMessages(e));
        }
        List<ContentBlock> blocks = convert(result.content());
        if (blocks.isEmpty() && result.structuredContent() != null) {
            blocks.add(new TextBlock(Json.pretty(result.structuredContent())));
        }
        if (blocks.isEmpty()) {
            blocks.add(new TextBlock("(no output)"));
        }
        return new ToolOutput(blocks, Boolean.TRUE.equals(result.isError()), Map.of());
    }

    /** MCP 的内容条目 → 领域内容块：文字、图片直接对应；资源有文字就放文字；音频等暂不支持的给个占位。 */
    static List<ContentBlock> convert(List<McpSchema.Content> items) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (items == null) {
            return blocks;
        }
        for (McpSchema.Content item : items) {
            switch (item) {
                case McpSchema.TextContent text -> blocks.add(new TextBlock(text.text() == null ? "" : text.text()));
                case McpSchema.ImageContent image ->
                        blocks.add(ImageBlock.base64(image.mimeType() == null ? "image/png" : image.mimeType(), image.data()));
                case McpSchema.EmbeddedResource embedded -> blocks.add(new TextBlock(resourceText(embedded.resource())));
                case McpSchema.ResourceLink link ->
                        blocks.add(new TextBlock(("Resource link: " + link.uri() + " " + (link.name() == null ? "" : link.name())).strip()));
                case McpSchema.AudioContent audio -> blocks.add(new TextBlock("(audio content omitted)"));
                default -> blocks.add(new TextBlock(Json.write(Json.tree(item))));
            }
        }
        return blocks;
    }

    private static String resourceText(McpSchema.ResourceContents resource) {
        if (resource instanceof McpSchema.TextResourceContents text) {
            return text.text();
        }
        return "(binary resource " + resource.uri() + ", " + resource.mimeType() + ")";
    }
}
