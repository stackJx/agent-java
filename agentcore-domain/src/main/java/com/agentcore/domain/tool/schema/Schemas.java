package com.agentcore.domain.tool.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造 JSON Schema 的小工具：
 * <pre>
 * Schemas.object()
 *     .string("path", "文件路径", true)
 *     .integer("limit", "最多返回多少行", false)
 *     .build();
 * </pre>
 */
public final class Schemas {

    private Schemas() {
    }

    public static ObjectSchema object() {
        return new ObjectSchema();
    }

    public static Map<String, Object> string(String description) {
        return withDesc(Map.of("type", "string"), description);
    }

    public static Map<String, Object> integer(String description) {
        return withDesc(Map.of("type", "integer"), description);
    }

    public static Map<String, Object> number(String description) {
        return withDesc(Map.of("type", "number"), description);
    }

    public static Map<String, Object> bool(String description) {
        return withDesc(Map.of("type", "boolean"), description);
    }

    public static Map<String, Object> enumOf(List<String> values, String description) {
        return withDesc(Map.of("type", "string", "enum", List.copyOf(values)), description);
    }

    public static Map<String, Object> array(Map<String, Object> items, String description) {
        return withDesc(Map.of("type", "array", "items", items), description);
    }

    private static Map<String, Object> withDesc(Map<String, Object> base, String description) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        if (description != null && !description.isEmpty()) {
            m.put("description", description);
        }
        return m;
    }

    public static final class ObjectSchema {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();
        private Boolean additionalProperties;

        public ObjectSchema property(String name, Map<String, Object> schema, boolean isRequired) {
            properties.put(name, schema);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        public ObjectSchema string(String name, String description, boolean isRequired) {
            return property(name, Schemas.string(description), isRequired);
        }

        public ObjectSchema integer(String name, String description, boolean isRequired) {
            return property(name, Schemas.integer(description), isRequired);
        }

        public ObjectSchema number(String name, String description, boolean isRequired) {
            return property(name, Schemas.number(description), isRequired);
        }

        public ObjectSchema bool(String name, String description, boolean isRequired) {
            return property(name, Schemas.bool(description), isRequired);
        }

        public ObjectSchema enumOf(String name, List<String> values, String description, boolean isRequired) {
            return property(name, Schemas.enumOf(values, description), isRequired);
        }

        public ObjectSchema array(String name, Map<String, Object> items, String description, boolean isRequired) {
            return property(name, Schemas.array(items, description), isRequired);
        }

        public ObjectSchema strict() {
            this.additionalProperties = false;
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "object");
            m.put("properties", new LinkedHashMap<>(properties));
            if (!required.isEmpty()) {
                m.put("required", List.copyOf(required));
            }
            if (additionalProperties != null) {
                m.put("additionalProperties", additionalProperties);
            }
            return m;
        }
    }
}
