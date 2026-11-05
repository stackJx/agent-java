package com.agentcore.interfaces;

import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.call;
import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.reply;
import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.application.AgentService;
import com.agentcore.domain.agent.AgentDefinition;
import com.agentcore.domain.agent.AgentEngine;
import com.agentcore.domain.agent.EngineSettings;
import com.agentcore.domain.permission.PermissionPolicy;
import com.agentcore.domain.tool.ToolRegistry;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.infrastructure.persistence.InMemoryConversationRepository;
import com.agentcore.infrastructure.tool.builtin.BuiltinTools;
import com.agentcore.interfaces.cli.Ansi;
import com.agentcore.interfaces.cli.ChatRepl;
import com.agentcore.interfaces.cli.ConsoleApprover;
import com.agentcore.interfaces.cli.ConsoleIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatReplTest {

    @TempDir
    Path dir;

    @Test
    void replRendersToolsApprovalsAndCommands() throws Exception {
        ScriptedModelGateway model = new ScriptedModelGateway()
                .then(reply("我先建文件。", call("write_file", "path", "hello.txt", "content", "你好\n"),
                        call("bash", "command", "rm -rf /tmp/nothing")))
                .then(reply("", call("bash", "command", "cat hello.txt")))
                .then(reply("完成：文件内容是「你好」。"));
        String input = "/tools\n写个文件\ny\na\n/usage\n/exit\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleIO io = new ConsoleIO(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        Ansi ansi = new Ansi(false);
        AgentEngine engine = AgentEngine.builder()
                .definition(new AgentDefinition("cli", "", EngineSettings.defaults(dir)))
                .model(model).tools(new ToolRegistry(BuiltinTools.select(List.of("write_file", "bash"))))
                .permissions(PermissionPolicy.of("default", List.of(), List.of("bash(rm -rf *)"), List.of()))
                .approver(new ConsoleApprover(io, ansi)).build();
        AgentService svc = new AgentService(engine, new InMemoryConversationRepository());
        String id = svc.startConversation(Map.of()).id().value();
        int code = new ChatRepl(svc, null, Map.of(), io, ansi, true, id).run();
        assertEquals(0, code);

        String stdout = out.toString(StandardCharsets.UTF_8);
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertEquals("你好\n", Files.readString(dir.resolve("hello.txt")));
        assertTrue(stdout.contains("我先建文件。") && stdout.contains("完成：文件内容是「你好」。"), stdout);
        assertTrue(stderr.contains("write_file") && stderr.contains("bash"), "tools listed");
        assertTrue(stderr.contains("? 允许执行 write_file(hello.txt)"), stderr);
        assertTrue(stderr.contains("denied by rule bash(rm -rf *)"), stderr);     // deny 规则：不询问直接拒绝
        assertTrue(stderr.contains("⏺ bash(cat hello.txt)"), stderr);
        assertTrue(stderr.contains("请求 3 次"), stderr);
    }
}
