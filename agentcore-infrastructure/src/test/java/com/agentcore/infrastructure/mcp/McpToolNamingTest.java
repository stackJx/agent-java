package com.agentcore.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpToolNamingTest {

    @Test
    void namesStayUniqueAfterSanitizingAndTruncating() {
        Set<String> taken = new HashSet<>();
        assertEquals("mcp__github__create_issue", McpServerManager.uniqueName(McpTool.nameFor("github", "create.issue"), taken));
        assertEquals("mcp__github__create_issue_2", McpServerManager.uniqueName(McpTool.nameFor("github", "create issue"), taken),
                "create.issue 和 create issue 替换字符后撞名");

        String longBase = McpTool.nameFor("server", "x".repeat(100));
        String first = McpServerManager.uniqueName(longBase, taken);
        String second = McpServerManager.uniqueName(longBase + "-other", taken);
        assertEquals(McpTool.MAX_NAME_LENGTH, first.length());
        assertEquals(McpTool.MAX_NAME_LENGTH, second.length());
        assertTrue(second.endsWith("_2"));
        assertNotEquals(first, second);
    }
}
