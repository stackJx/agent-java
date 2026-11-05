package com.agentcore.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置：YAML 合并 + 环境变量插值 + 点路径读取。
 *
 * <p>查找顺序（后者覆盖前者）：{@code ~/.agentcore/config.yaml} → {@code ./.agentcore/config.yaml}
 * → {@code ./agentcore.yaml} → 显式指定的文件。字符串支持 {@code ${ENV}} / {@code ${ENV:-默认值}}。
 */
public final class Settings {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

    public static final String DEFAULTS = """
            model: null
            providers: {}
            agent:
              name: agentcore
              max_turns: 100
              instructions: ""
              system_prompt: null
              system_prompt_file: null
            tools: null
            permissions:
              mode: default
              allow: []
              deny: []
              ask: []
            mcp_servers: {}
            subagents: {}
            subagent_max_depth: 1
            memory:
              enabled: true
              dir: ${AGENTCORE_HOME}/memory
            skills:
              enabled: true
              dirs: [.agentcore/skills, "${AGENTCORE_HOME}/skills"]
            sessions:
              enabled: true
              dir: ${AGENTCORE_HOME}/sessions
            persistence:
              jdbc:
                url: null
                username: null
                password: null
                platform: auto
                table_prefix: agent_
                initialize_schema: create-if-missing
                memory_scope: default
            context:
              threshold: 0.75
              max_tokens: 0
              summarizer_model: null
            project_instructions: true
            server:
              port: 8080
              approval_timeout_seconds: 300
            """;

    private final Map<String, Object> data;

    public Settings(Map<String, Object> data) {
        this.data = data;
    }

    public static Path home(Function<String, String> env) {
        String h = env.apply("AGENTCORE_HOME");
        return h != null && !h.isBlank() ? Path.of(h) : Path.of(System.getProperty("user.home"), ".agentcore");
    }

    public static Settings load(Path explicit, Path cwd) {
        return load(explicit, cwd, System::getenv);
    }

    public static Settings load(Path explicit, Path cwd, Function<String, String> env) {
        Path home = home(env);
        Function<String, String> envWithHome = k -> k.equals("AGENTCORE_HOME") ? home.toString() : env.apply(k);
        Map<String, Object> merged = parse(DEFAULTS);
        List<Path> candidates = new ArrayList<>(List.of(home.resolve("config.yaml"), cwd.resolve(".agentcore/config.yaml"),
                cwd.resolve("agentcore.yaml")));
        if (explicit != null) {
            if (!Files.isRegularFile(explicit)) {
                throw new IllegalArgumentException("config file not found: " + explicit);
            }
            candidates.add(explicit);
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try {
                    merged = deepMerge(merged, parse(Files.readString(p)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> interpolated = (Map<String, Object>) interpolate(merged, envWithHome);
        Settings s = new Settings(interpolated);
        if (s.string("model", null) == null) {
            String m = defaultModel(env);
            if (m != null) {
                s.set("model", m);
            }
        }
        return s;
    }

    /** 没配置模型时，按已有的 API Key 环境变量推断。 */
    public static String defaultModel(Function<String, String> env) {
        if (env.apply("AGENTCORE_MODEL") != null) {
            return env.apply("AGENTCORE_MODEL");
        }
        if (env.apply("ANTHROPIC_API_KEY") != null || env.apply("ANTHROPIC_AUTH_TOKEN") != null) {
            return "anthropic:claude-opus-5-5";
        }
        if (env.apply("DEEPSEEK_API_KEY") != null) {
            return "deepseek:deepseek-v4-pro";
        }
        if (env.apply("OPENAI_API_KEY") != null) {
            return "openai:gpt-5";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String yaml) {
        try {
            Object o = YAML.readValue(yaml, Object.class);
            return o instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid YAML: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> over) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        over.forEach((k, v) -> {
            if (v instanceof Map<?, ?> vm && out.get(k) instanceof Map<?, ?> bm) {
                out.put(k, deepMerge((Map<String, Object>) bm, (Map<String, Object>) vm));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    static Object interpolate(Object o, Function<String, String> env) {
        if (o instanceof String s) {
            Matcher m = ENV.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = env.apply(m.group(1));
                if (v == null) {
                    v = m.group(2) == null ? "" : m.group(2);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (o instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), interpolate(v, env)));
            return out;
        }
        if (o instanceof List<?> list) {
            return list.stream().map(v -> interpolate(v, env)).toList();
        }
        return o;
    }

    // ------------------------------------------------------------------ 读取

    public Object get(String path) {
        Object cur = data;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(part);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    public void set(String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cur = data;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(parts[i], next);
            } else if (!(next instanceof LinkedHashMap<?, ?>)) {
                next = new LinkedHashMap<>((Map<String, Object>) next);
                cur.put(parts[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(parts[parts.length - 1], value);
    }

    public String string(String path, String def) {
        Object v = get(path);
        return v == null || String.valueOf(v).isBlank() || "null".equals(String.valueOf(v)) ? def : String.valueOf(v);
    }

    public int integer(String path, int def) {
        Object v = get(path);
        return v instanceof Number n ? n.intValue() : v == null ? def : Integer.parseInt(String.valueOf(v).strip());
    }

    public double decimal(String path, double def) {
        Object v = get(path);
        return v instanceof Number n ? n.doubleValue() : v == null ? def : Double.parseDouble(String.valueOf(v).strip());
    }

    public boolean bool(String path, boolean def) {
        Object v = get(path);
        return v instanceof Boolean b ? b : v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    public List<String> strings(String path) {
        Object v = get(path);
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String path) {
        Object v = get(path);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> mapOfMaps(String path) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        map(path).forEach((k, v) -> out.put(k, v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of()));
        return out;
    }

    public Map<String, Object> asMap() {
        return data;
    }
}
