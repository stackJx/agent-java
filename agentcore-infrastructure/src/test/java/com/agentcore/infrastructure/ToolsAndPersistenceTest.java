package com.agentcore.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.memory.model.MemoryEntry;
import com.agentcore.domain.memory.model.MemoryType;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.infrastructure.llm.ModelGatewayFactory;
import com.agentcore.infrastructure.persistence.FileMemoryRepository;
import com.agentcore.infrastructure.persistence.FileSkillCatalog;
import com.agentcore.infrastructure.persistence.JsonlConversationRepository;
import com.agentcore.infrastructure.tool.annotation.AgentTool;
import com.agentcore.infrastructure.tool.annotation.AnnotatedTools;
import com.agentcore.infrastructure.tool.annotation.Param;
import com.agentcore.infrastructure.tool.builtin.BuiltinTools;
import com.agentcore.infrastructure.tool.builtin.WebTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolsAndPersistenceTest {

    @TempDir
    Path dir;

    ToolContext ctx() {
        return new ToolContext(dir, "c1", "call", new ConcurrentHashMap<>(), m -> { }, new CancellationToken(), Map.of(), 0);
    }

    Tool tool(String name) {
        return BuiltinTools.all().get(name);
    }

    @Test
    void readWriteEditWithStalenessGuard() throws Exception {
        ToolContext ctx = ctx();
        assertTrue(tool("write_file").execute(Map.of("path", "a.txt", "content", "hello\nworld\n"), ctx).text().startsWith("Created"));
        assertTrue(tool("read_file").execute(Map.of("path", "a.txt"), ctx).text().contains("     1\thello"));
        assertTrue(tool("edit_file").execute(Map.of("path", "a.txt", "old_string", "world", "new_string", "there"), ctx).text().startsWith("Edited"));
        assertEquals("hello\nthere\n", Files.readString(dir.resolve("a.txt")));

        Files.writeString(dir.resolve("a.txt"), "changed\n");
        Files.setLastModifiedTime(dir.resolve("a.txt"), FileTime.from(Instant.now().plusSeconds(5)));
        ToolException stale = assertThrows(ToolException.class,
                () -> tool("edit_file").execute(Map.of("path", "a.txt", "old_string", "changed", "new_string", "x"), ctx));
        assertTrue(stale.getMessage().contains("modified since"));

        Files.writeString(dir.resolve("b.txt"), "keep me");
        assertThrows(ToolException.class, () -> tool("write_file").execute(Map.of("path", "b.txt", "content", "boom"), ctx));
        Files.writeString(dir.resolve("b.txt"), "x x");
        tool("read_file").execute(Map.of("path", "b.txt"), ctx);
        assertThrows(ToolException.class, () -> tool("edit_file").execute(Map.of("path", "b.txt", "old_string", "x", "new_string", "y"), ctx));
        assertTrue(tool("edit_file").execute(Map.of("path", "b.txt", "old_string", "x", "new_string", "y", "replace_all", true), ctx)
                .text().contains("2 occurrence"));
    }

    @Test
    void readFileOffsetAndImage() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            sb.append("line").append(i).append('\n');
        }
        Files.writeString(dir.resolve("long.txt"), sb);
        String out = tool("read_file").execute(Map.of("path", "long.txt", "offset", 10, "limit", 5), ctx()).text();
        assertTrue(out.contains("    10\tline10") && out.contains("    14\tline14") && out.contains("offset=15"), out);
        Files.write(dir.resolve("x.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        ToolOutput img = tool("read_file").execute(Map.of("path", "x.png"), ctx());
        assertInstanceOf(ImageBlock.class, img.content().get(1));
    }

    @Test
    void globGrepBashTodo() throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/Main.java"), "class Main {\n  void hello() {}\n}\n");
        Files.createDirectories(dir.resolve("node_modules"));
        Files.writeString(dir.resolve("node_modules/skip.java"), "void hello() {}");
        String g = tool("glob").execute(Map.of("pattern", "**/*.java"), ctx()).text();
        assertTrue(g.contains("src/Main.java") && !g.contains("skip"), g);
        String gr = tool("grep").execute(Map.of("pattern", "void \\w+", "output", "content"), ctx()).text();
        assertTrue(gr.contains("Main.java:2:") && !gr.contains("skip"), gr);
        assertEquals("No matches.", tool("grep").execute(Map.of("pattern", "nothing_here"), ctx()).text());

        String b = tool("bash").execute(Map.of("command", "echo hi; exit 3"), ctx()).text();
        assertTrue(b.contains("hi") && b.contains("[exit code 3]"), b);
        ToolException t = assertThrows(ToolException.class, () -> tool("bash").execute(Map.of("command", "sleep 5", "timeout", 1), ctx()));
        assertTrue(t.getMessage().contains("timed out"));

        ToolContext c = ctx();
        String todo = tool("todo_write").execute(Map.of("todos", List.of(Map.of("content", "a", "status", "completed"),
                Map.of("content", "b", "status", "in_progress"))), c).text();
        assertTrue(todo.contains("1/2 done"));
        assertNotNull(c.state().get("todos"));
    }

    @Test
    void htmlToText() {
        String t = WebTools.htmlToText("<html><head><title>T</title><style>x{}</style></head><body><h1>Hi</h1><p>a <a href='https://x.y'>link</a></p><script>bad()</script></body></html>");
        assertTrue(t.startsWith("# T") && t.contains("# Hi") && t.contains("link (https://x.y)") && !t.contains("bad()"), t);
    }

    @Test
    void jsonlRepositoryPersistsAndReplays() throws Exception {
        JsonlConversationRepository repo = new JsonlConversationRepository(dir.resolve("sessions"));
        Conversation c = Conversation.start();
        c.addUserInput(List.of(new TextBlock("你好")));
        repo.save(c);
        c.addAssistantReply(Message.assistant(List.of(ToolCall.of("t1", "bash", Map.of("command", "ls"))), null), TokenUsage.of(100, 20));
        repo.save(c);

        // 模拟崩溃：工具结果还没写入，文件末尾还有半行
        Path f = dir.resolve("sessions").resolve(c.id().value() + ".jsonl");
        Files.writeString(f, Files.readString(f) + "{\"t\":\"msg\",\"m\":{\"ro");

        JsonlConversationRepository fresh = new JsonlConversationRepository(dir.resolve("sessions"));
        Conversation loaded = fresh.findById(c.id()).orElseThrow();
        assertEquals(2, loaded.size());
        assertEquals("ls", loaded.messages().get(1).toolCalls().get(0).arguments().get("command"));
        assertEquals(1, loaded.usage().requests());
        assertEquals("你好", fresh.list(10).get(0).title());
        assertEquals(c.id(), fresh.findLatest().orElseThrow().id());
        assertEquals(1, loaded.pendingToolCalls().size()); // 引擎下次运行时会补上「已中断」结果
    }

    @Test
    void memoryAndSkills() throws Exception {
        FileMemoryRepository mem = new FileMemoryRepository(dir.resolve("mem"));
        mem.save(new MemoryEntry("User Likes Tea", "用户喜欢喝茶: 'oolong'", MemoryType.USER, "乌龙茶", LocalDate.now()));
        assertEquals("user-likes-tea", mem.list().get(0).name());
        assertEquals("乌龙茶", mem.find("user-likes-tea").orElseThrow().content());
        assertEquals("用户喜欢喝茶: 'oolong'", mem.find("user-likes-tea").orElseThrow().description());
        assertEquals(1, mem.search("乌龙茶", 5).size());
        assertTrue(mem.delete("user-likes-tea"));
        assertTrue(mem.list().isEmpty());

        Path skill = dir.resolve("skills/weekly-report");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: weekly-report\ndescription: 写周报时使用\n---\n# 步骤\n1. 汇总本周提交\n");
        FileSkillCatalog cat = new FileSkillCatalog(List.of(dir.resolve("skills"), dir.resolve("missing")));
        assertEquals("写周报时使用", cat.find("weekly-report").orElseThrow().description());
        assertTrue(cat.instructions(cat.list().get(0)).contains("汇总本周提交"));
    }

    // ---------------------------------------------------------------- 注解工具

    public enum Unit { C, F }

    public record Item(@Param("名称") String title, @Param(value = "数量", required = false) Integer count) {
    }

    public static class Weather {
        @AgentTool(description = "查询城市天气", readOnly = true)
        public String getWeather(@Param("城市名") String city, @Param(value = "温度单位", required = false) Unit unit,
                                 Optional<Integer> days) {
            return city + " 晴 25°" + (unit == null ? "C" : unit.name()) + days.map(d -> " x" + d).orElse("");
        }

        @AgentTool(name = "add_items", description = "添加条目")
        public Map<String, Object> addItems(List<Item> items, ToolContext ctx) {
            return Map.of("count", items.size(), "cwd_set", ctx != null);
        }
    }

    @Test
    void annotatedToolsInferSchemaAndConvertArguments() throws Exception {
        List<Tool> tools = AnnotatedTools.from(new Weather());
        assertEquals(List.of("add_items", "get_weather"), tools.stream().map(Tool::name).toList());
        Tool w = tools.get(1);
        assertTrue(w.readOnly());
        Map<String, Object> props = (Map<String, Object>) w.inputSchema().get("properties");
        assertEquals(Map.of("type", "string", "description", "城市名"), props.get("city"));
        assertEquals(List.of("C", "F"), ((Map<?, ?>) props.get("unit")).get("enum"));
        assertEquals(List.of("city"), w.inputSchema().get("required"));
        assertEquals("北京 晴 25°F x3", w.execute(Map.of("city", "北京", "unit", "F", "days", 3), ctx()).text());

        Tool add = tools.get(0);
        Map<?, ?> itemSchema = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) add.inputSchema().get("properties")).get("items")).get("items");
        assertEquals(List.of("title"), itemSchema.get("required"));
        assertTrue(add.execute(Map.of("items", List.of(Map.of("title", "a"), Map.of("title", "b", "count", 2))), ctx())
                .text().contains("\"count\" : 2"));
    }

    @Test
    void defaultModelIsInferredFromApiKeys() {
        assertEquals("deepseek:deepseek-v4-pro", ModelGatewayFactory.defaultModel(Map.of("DEEPSEEK_API_KEY", "k")::get));
        assertEquals("anthropic:claude-opus-5-5", ModelGatewayFactory.defaultModel(Map.of("ANTHROPIC_API_KEY", "k", "DEEPSEEK_API_KEY", "k")::get));
        assertEquals("mock", ModelGatewayFactory.defaultModel(Map.of("AGENTCORE_MODEL", "mock", "OPENAI_API_KEY", "k")::get));
        assertNull(ModelGatewayFactory.defaultModel(Map.<String, String>of()::get));
    }
}
