package com.agentcore.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown 开头的 YAML frontmatter 读写。 */
public final class Frontmatter {

    public record Doc(Map<String, Object> meta, String body) {
    }

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    /** 文件开头 "---" 与 "---" 之间的 YAML。 */
    private static final Pattern HEADER = Pattern.compile("\\A---\\s*\\n(.*?)\\n---\\s*\\n?", Pattern.DOTALL);

    private Frontmatter() {
    }

    public static Doc parse(String text) {
        Matcher header = HEADER.matcher(text);
        if (!header.find()) {
            return new Doc(Map.of(), text);
        }
        Map<String, Object> meta;
        try {
            meta = YAML.readValue(header.group(1), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception invalidYaml) {
            meta = Map.of();
        }
        return new Doc(meta == null ? Map.of() : meta, text.substring(header.end()));
    }

    public static String render(Map<String, Object> meta, String body) {
        try {
            return "---\n" + YAML.writeValueAsString(meta) + "---\n\n" + body.strip() + "\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
