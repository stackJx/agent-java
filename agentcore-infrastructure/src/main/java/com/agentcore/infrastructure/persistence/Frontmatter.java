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
    private static final Pattern FM = Pattern.compile("\\A---\\s*\\n(.*?)\\n---\\s*\\n?", Pattern.DOTALL);

    private Frontmatter() {
    }

    public static Doc parse(String text) {
        Matcher m = FM.matcher(text);
        if (!m.find()) {
            return new Doc(Map.of(), text);
        }
        Map<String, Object> meta;
        try {
            meta = YAML.readValue(m.group(1), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            meta = Map.of();
        }
        return new Doc(meta == null ? Map.of() : meta, text.substring(m.end()));
    }

    public static String render(Map<String, Object> meta, String body) {
        try {
            return "---\n" + YAML.writeValueAsString(meta) + "---\n\n" + body.strip() + "\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
