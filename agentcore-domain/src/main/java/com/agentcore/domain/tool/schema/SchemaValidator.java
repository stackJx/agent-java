package com.agentcore.domain.tool.schema;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 轻量 JSON Schema 校验（type / required / enum / properties / items / additionalProperties / anyOf /
 * min/max 等常用子集），并做温和的类型转换：弱一些的模型常把数字、布尔写成字符串。
 *
 * <p>校验一个值的顺序：anyOf/oneOf → 多类型 → 单类型（先转换再判断）→ enum → 长度/大小 → 数组元素 → 对象属性。
 * 每一步都可能产出转换后的新值，最终结果在 {@link Result#value()} 里。
 */
public final class SchemaValidator {

    public record Result(Object value, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    private SchemaValidator() {
    }

    public static Result validate(Object value, Map<String, Object> schema) {
        List<String> errors = new ArrayList<>();
        Object converted = check(value, schema, "$", errors);
        return new Result(converted, errors);
    }

    /** 校验 value，错误追加到 errors（path 是 "$.a.b[0]" 这样的位置），返回转换后的值。 */
    private static Object check(Object value, Map<String, Object> schema, String path, List<String> errors) {
        if (schema == null || schema.isEmpty()) {
            return value;
        }
        Object alternatives = schema.containsKey("anyOf") ? schema.get("anyOf") : schema.get("oneOf");
        if (alternatives instanceof List<?> options) {
            return checkAlternatives(value, options, path, errors);
        }
        Object type = schema.get("type");
        if (type instanceof List<?> types) {
            return checkTypeUnion(value, schema, types, path, errors);
        }
        if (type instanceof String expected) {
            value = coerce(value, expected);
            if (!matchesType(value, expected)) {
                errors.add(path + ": expected " + expected + ", got " + typeName(value));
                return value;
            }
            if (expected.equals("integer") && value instanceof Number number && !(value instanceof Long) && !(value instanceof Integer)) {
                value = number.longValue();
            }
        }
        checkEnum(value, schema, path, errors);
        checkBounds(value, schema, path, errors);
        if (value instanceof List<?> list && schema.get("items") instanceof Map<?, ?> itemSchema) {
            value = checkItems(list, asSchema(itemSchema), path, errors);
        }
        if (value instanceof Map<?, ?> object && (schema.containsKey("properties") || schema.containsKey("required"))) {
            value = checkProperties(object, schema, path, errors);
        }
        return value;
    }

    /** anyOf / oneOf：第一个能通过的分支胜出（这里不区分两者）。 */
    private static Object checkAlternatives(Object value, List<?> options, String path, List<String> errors) {
        for (Object option : options) {
            List<String> optionErrors = new ArrayList<>();
            Object converted = check(value, asSchema(option), path, optionErrors);
            if (optionErrors.isEmpty()) {
                return converted;
            }
        }
        errors.add(path + ": does not match any allowed schema");
        return value;
    }

    /** "type": ["string", "null"] 这样的多类型：逐个类型试，第一个能通过的胜出。 */
    private static Object checkTypeUnion(Object value, Map<String, Object> schema, List<?> types, String path, List<String> errors) {
        for (Object type : types) {
            Map<String, Object> single = new LinkedHashMap<>(schema);
            single.put("type", type);
            List<String> typeErrors = new ArrayList<>();
            Object converted = check(value, single, path, typeErrors);
            if (typeErrors.isEmpty()) {
                return converted;
            }
        }
        errors.add(path + ": expected one of types " + types + ", got " + typeName(value));
        return value;
    }

    /** enum：数字按数值比较（1 和 1.0 算同一个）。 */
    private static void checkEnum(Object value, Map<String, Object> schema, String path, List<String> errors) {
        if (!(schema.get("enum") instanceof List<?> allowed) || allowed.contains(value)) {
            return;
        }
        boolean sameNumber = value instanceof Number number && allowed.stream()
                .anyMatch(option -> option instanceof Number n && n.doubleValue() == number.doubleValue());
        if (!sameNumber) {
            errors.add(path + ": must be one of " + allowed + ", got " + value);
        }
    }

    /** 字符串长度 minLength / maxLength，数字大小 minimum / maximum。 */
    private static void checkBounds(Object value, Map<String, Object> schema, String path, List<String> errors) {
        if (value instanceof String text) {
            if (schema.get("minLength") instanceof Number min && text.length() < min.intValue()) {
                errors.add(path + ": shorter than " + min);
            }
            if (schema.get("maxLength") instanceof Number max && text.length() > max.intValue()) {
                errors.add(path + ": longer than " + max);
            }
        }
        if (value instanceof Number number && !(value instanceof Boolean)) {
            if (schema.get("minimum") instanceof Number min && number.doubleValue() < min.doubleValue()) {
                errors.add(path + ": must be >= " + min);
            }
            if (schema.get("maximum") instanceof Number max && number.doubleValue() > max.doubleValue()) {
                errors.add(path + ": must be <= " + max);
            }
        }
    }

    private static List<Object> checkItems(List<?> list, Map<String, Object> itemSchema, String path, List<String> errors) {
        List<Object> converted = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            converted.add(check(list.get(i), itemSchema, path + "[" + i + "]", errors));
        }
        return converted;
    }

    /** required 缺了报错；有 schema 的属性递归校验；additionalProperties=false 时多出来的属性报错。 */
    private static Map<String, Object> checkProperties(Map<?, ?> object, Map<String, Object> schema, String path, List<String> errors) {
        if (schema.get("required") instanceof List<?> required) {
            for (Object name : required) {
                if (!object.containsKey(name)) {
                    errors.add(path + ": missing required property '" + name + "'");
                }
            }
        }
        Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> p ? asSchema(p) : Map.of();
        boolean closed = Boolean.FALSE.equals(schema.get("additionalProperties"));
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (properties.get(name) instanceof Map<?, ?> propertySchema) {
                converted.put(name, check(entry.getValue(), asSchema(propertySchema), path + "." + name, errors));
            } else {
                if (closed) {
                    errors.add(path + ": unexpected property '" + name + "'");
                }
                converted.put(name, entry.getValue());
            }
        }
        return converted;
    }

    /** 字符串形式的数字、布尔转成真正的类型；转不了就原样返回，交给类型检查报错。 */
    private static Object coerce(Object value, String type) {
        if (!(value instanceof String raw)) {
            return value;
        }
        String text = raw.strip();
        switch (type) {
            case "integer":
                if (INTEGER.matcher(text).matches()) {
                    try {
                        return Long.parseLong(text);
                    } catch (NumberFormatException tooLarge) {
                        return value;
                    }
                }
                break;
            case "number":
                if (NUMBER.matcher(text).matches()) {
                    boolean decimal = text.contains(".") || text.contains("e") || text.contains("E");
                    return decimal ? Double.parseDouble(text) : Long.parseLong(text);
                }
                break;
            case "boolean":
                if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
                    return Boolean.parseBoolean(text);
                }
                break;
            default:
                break;
        }
        return value;
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> isWholeNumber(value);
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            case "null" -> value == null;
            default -> true;
        };
    }

    /** 整数类型，或者没有小数部分的浮点数（例如 3.0）。 */
    private static boolean isWholeNumber(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger) {
            return true;
        }
        return value instanceof Number number && !(value instanceof Boolean)
                && number.doubleValue() == Math.rint(number.doubleValue()) && !Double.isInfinite(number.doubleValue());
    }

    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof Map) {
            return "object";
        }
        return value.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asSchema(Object schema) {
        return (Map<String, Object>) schema;
    }
}
