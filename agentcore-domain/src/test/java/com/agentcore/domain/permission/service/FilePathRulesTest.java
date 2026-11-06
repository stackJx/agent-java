package com.agentcore.domain.permission.service;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 文件工具的权限规则按解析后的真实路径匹配：.. 和符号链接都绕不过去。 */
class FilePathRulesTest {

    static final Tool READ = pathTool("read_file", true);
    static final Tool WRITE = pathTool("write_file", false);

    static Tool pathTool(String name, boolean readOnly) {
        return FunctionTool.builder(name).readOnly(readOnly).pathArgument("path")
                .handler((args, context) -> ToolOutput.text("ok")).build();
    }

    @TempDir
    Path root;
    /** 工作目录：root/work；它旁边的 root/outside 是工作目录之外。 */
    Path work;
    Path outside;

    @BeforeEach
    void createDirectories() throws Exception {
        work = Files.createDirectories(root.resolve("work"));
        outside = Files.createDirectories(root.resolve("outside"));
        Files.createDirectories(work.resolve("src"));
    }

    Decision check(PermissionPolicy policy, Tool tool, String path) {
        return policy.check(tool, Map.of("path", path), context()).decision();
    }

    ToolContext context() {
        return new ToolContext(work, "c1", "call-1", Map.of(), null, CancellationToken.NONE, Map.of(), 0);
    }

    @Test
    void dotDotCannotSneakOutOfAnAllowedDirectory() {
        PermissionPolicy policy = RulePermissionPolicy.of("default", List.of("write_file(src/*)"), null, null);
        assertEquals(Decision.ALLOW, check(policy, WRITE, "src/A.java"));
        assertEquals(Decision.ALLOW, check(policy, WRITE, work.resolve("src/B.java").toString()), "绝对路径写法也能匹配相对的规则");
        assertEquals(Decision.ASK, check(policy, WRITE, "src/../../outside/passwd"), "以前会被 src/* 放行");
        assertEquals(Decision.ASK, check(policy, WRITE, "src/../build.gradle"));
    }

    @Test
    void relativeAndRoundaboutPathsCannotDodgeADenyRule() {
        String outsidePattern = outside + "/*";
        PermissionPolicy policy = RulePermissionPolicy.of("auto", null, List.of("write_file(" + outsidePattern + ")"), null);
        assertEquals(Decision.DENY, check(policy, WRITE, "../outside/passwd"), "相对路径以前能躲过 deny");
        assertEquals(Decision.DENY, check(policy, WRITE, outside + "/../outside/./passwd"));
        assertEquals(Decision.ALLOW, check(policy, WRITE, "src/A.java"));
    }

    @Test
    void symlinksAreFollowedInTheCallPath() throws Exception {
        Files.createSymbolicLink(work.resolve("src/link"), outside);
        PermissionPolicy allowSrc = RulePermissionPolicy.of("default", List.of("write_file(src/*)"), null, null);
        assertEquals(Decision.ASK, check(allowSrc, WRITE, "src/link/new-file.txt"), "src/link 指向外面，不算在 src 里");

        PermissionPolicy denyOutside = RulePermissionPolicy.of("auto", null, List.of("write_file(" + outside + "/*)"), null);
        assertEquals(Decision.DENY, check(denyOutside, WRITE, "src/link/new-file.txt"));
    }

    @Test
    void symlinksAreFollowedInTheRuleDirectory() throws Exception {
        // 像 macOS 的 /etc → /private/etc：规则写的是链接，调用用的是真实路径
        Path alias = Files.createSymbolicLink(root.resolve("alias"), outside);
        PermissionPolicy policy = RulePermissionPolicy.of("auto", null, List.of("write_file(" + alias + "/*)"), null);
        assertEquals(Decision.DENY, check(policy, WRITE, outside + "/secret.txt"));
    }

    @Test
    void readingOutsideTheWorkingDirectoryAsks() throws Exception {
        PermissionPolicy policy = PermissionPolicy.defaults();
        assertEquals(Decision.ALLOW, check(policy, READ, "src/A.java"));
        assertEquals(Decision.ASK, check(policy, READ, "../outside/secret.txt"));
        assertEquals(Decision.ASK, check(policy, READ, "~/.ssh/id_rsa"));
        assertEquals("the path is outside the working directory",
                policy.check(READ, Map.of("path", "/etc/hostname"), context()).reason());
        Files.createSymbolicLink(work.resolve("link"), outside);
        assertEquals(Decision.ASK, check(policy, READ, "link/secret.txt"), "指向外面的符号链接也算外面");

        PermissionPolicy allowNotes = RulePermissionPolicy.of("default", List.of("read_file(" + outside + "/*)"), null, null);
        assertEquals(Decision.ALLOW, check(allowNotes, READ, "../outside/notes.md"), "规则可以显式放行工作目录之外的目录");
    }

    @Test
    void patternsStartingWithAWildcardMatchAnywhere() {
        PermissionPolicy policy = RulePermissionPolicy.of("default", null, null, List.of("read_file(*.env)"));
        assertEquals(Decision.ASK, check(policy, READ, "src/.env"));
        assertEquals(Decision.ALLOW, check(policy, READ, "src/A.java"));
    }

    @Test
    void withoutAContextPathsAreStillNormalized() {
        PermissionPolicy policy = RulePermissionPolicy.of("default", List.of("write_file(src/*)"), null, null);
        assertEquals(Decision.ALLOW, policy.check(WRITE, Map.of("path", "src/A.java")).decision());
        assertEquals(Decision.ASK, policy.check(WRITE, Map.of("path", "src/../../etc/passwd")).decision());
    }
}
