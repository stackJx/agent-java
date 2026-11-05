package com.agentcore.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.tool.SchemaValidator;
import com.agentcore.domain.tool.Schemas;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

    final Map<String, Object> schema = Schemas.object()
            .integer("n", "count", true)
            .bool("flag", "", false)
            .enumOf("mode", List.of("a", "b"), "", false)
            .array("tags", Schemas.string(null), "", false)
            .strict().build();

    @Test
    void coercesStringsFromWeakModels() {
        var r = SchemaValidator.validate(Map.of("n", "42", "flag", "true", "tags", List.of("x")), schema);
        assertTrue(r.valid(), r.errors().toString());
        assertEquals(Map.of("n", 42L, "flag", true, "tags", List.of("x")), r.value());
    }

    @Test
    void reportsAllProblems() {
        var r = SchemaValidator.validate(Map.of("mode", "c", "extra", 1), schema);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("missing required property 'n'")));
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("must be one of")));
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("unexpected property 'extra'")));
        assertFalse(SchemaValidator.validate(Map.of("n", true), schema).valid()); // boolean 不是 integer
        assertTrue(SchemaValidator.validate(Map.of("n", 3.0), schema).valid());   // 3.0 可视为整数
    }
}
