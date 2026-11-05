package com.agentcore.domain.tool.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 轻量 JSON Schema 校验（type / required / enum / properties / items / additionalProperties / anyOf /
 * min/max 等常用子集），并做温和的类型转换：弱一些的模型常把数字、布尔写成字符串。
 */
public final class SchemaValidator {

    public record Result(Object value, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private static final Pattern INT = Pattern.compile("-?\\d+");
    private static final Pattern NUM = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    private SchemaValidator() {
    }

    public static Result validate(Object value, Map<String, Object> schema) {
        List<String> errors = new ArrayList<>();
        Object v = check(value, schema, "$", errors);
        return new Result(v, errors);
    }

    @SuppressWarnings("unchecked")
    private static Object check(Object value, Map<String, Object> schema, String path, List<String> errors) {
        if (schema == null || schema.isEmpty()) {
            return value;
        }
        Object anyOf = schema.containsKey("anyOf") ? schema.get("anyOf") : schema.get("oneOf");
        if (anyOf instanceof List<?> options) {
            for (Object opt : options) {
                List<String> errs = new ArrayList<>();
                Object v = check(value, (Map<String, Object>) opt, path, errs);
                if (errs.isEmpty()) {
                    return v;
                }
            }
            errors.add(path + ": does not match any allowed schema");
            return value;
        }
        Object type = schema.get("type");
        if (type instanceof List<?> types) {
            for (Object t : types) {
                Map<String, Object> s = new LinkedHashMap<>(schema);
                s.put("type", t);
                List<String> errs = new ArrayList<>();
                Object v = check(value, s, path, errs);
                if (errs.isEmpty()) {
                    return v;
                }
            }
            errors.add(path + ": expected one of types " + types + ", got " + typeName(value));
            return value;
        }
        if (type instanceof String t) {
            value = coerce(value, t);
            if (!typeOk(value, t)) {
                errors.add(path + ": expected " + t + ", got " + typeName(value));
                return value;
            }
            if (t.equals("integer") && value instanceof Number n && !(value instanceof Long) && !(value instanceof Integer)) {
                value = n.longValue();
            }
        }
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value)
                && !(value instanceof Number n && allowed.stream().anyMatch(a -> a instanceof Number an && an.doubleValue() == n.doubleValue()))) {
            errors.add(path + ": must be one of " + allowed + ", got " + value);
        }
        if (value instanceof String s) {
            if (schema.get("minLength") instanceof Number min && s.length() < min.intValue()) {
                errors.add(path + ": shorter than " + min);
            }
            if (schema.get("maxLength") instanceof Number max && s.length() > max.intValue()) {
                errors.add(path + ": longer than " + max);
            }
        }
        if (value instanceof Number n && !(value instanceof Boolean)) {
            if (schema.get("minimum") instanceof Number min && n.doubleValue() < min.doubleValue()) {
                errors.add(path + ": must be >= " + min);
            }
            if (schema.get("maximum") instanceof Number max && n.doubleValue() > max.doubleValue()) {
                errors.add(path + ": must be <= " + max);
            }
        }
        if (value instanceof List<?> list && schema.get("items") instanceof Map<?, ?> itemSchema) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                out.add(check(list.get(i), (Map<String, Object>) itemSchema, path + "[" + i + "]", errors));
            }
            value = out;
        }
        if (value instanceof Map<?, ?> map && (schema.containsKey("properties") || schema.containsKey("required"))) {
            Map<String, Object> props = schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            Map<String, Object> out = new LinkedHashMap<>();
            if (schema.get("required") instanceof List<?> req) {
                for (Object r : req) {
                    if (!map.containsKey(r)) {
                        errors.add(path + ": missing required property '" + r + "'");
                    }
                }
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (props.get(k) instanceof Map<?, ?> ps) {
                    out.put(k, check(e.getValue(), (Map<String, Object>) ps, path + "." + k, errors));
                } else {
                    if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                        errors.add(path + ": unexpected property '" + k + "'");
                    }
                    out.put(k, e.getValue());
                }
            }
            value = out;
        }
        return value;
    }

    private static Object coerce(Object value, String type) {
        if (!(value instanceof String s)) {
            return value;
        }
        String v = s.strip();
        switch (type) {
            case "integer":
                if (INT.matcher(v).matches()) {
                    try {
                        return Long.parseLong(v);
                    } catch (NumberFormatException ignored) {
                        return value;
                    }
                }
                break;
            case "number":
                if (NUM.matcher(v).matches()) {
                    return v.contains(".") || v.contains("e") || v.contains("E") ? Double.parseDouble(v) : Long.parseLong(v);
                }
                break;
            case "boolean":
                if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
                    return Boolean.parseBoolean(v);
                }
                break;
            default:
                break;
        }
        return value;
    }

    private static boolean typeOk(Object v, String type) {
        return switch (type) {
            case "string" -> v instanceof String;
            case "integer" -> (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte
                    || v instanceof java.math.BigInteger)
                    || (v instanceof Number n && !(v instanceof Boolean) && n.doubleValue() == Math.rint(n.doubleValue())
                    && !Double.isInfinite(n.doubleValue()));
            case "number" -> v instanceof Number;
            case "boolean" -> v instanceof Boolean;
            case "array" -> v instanceof List;
            case "object" -> v instanceof Map;
            case "null" -> v == null;
            default -> true;
        };
    }

    private static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof List) {
            return "array";
        }
        if (v instanceof Map) {
            return "object";
        }
        return v.getClass().getSimpleName();
    }
}
