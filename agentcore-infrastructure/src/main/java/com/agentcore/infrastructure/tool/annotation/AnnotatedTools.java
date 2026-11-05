package com.agentcore.infrastructure.tool.annotation;

import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.tool.model.PermissionLevel;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JavaType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 扫描对象上带 {@link AgentTool} 的方法，生成 {@link Tool}：Schema 由 Java 类型推断，参数用 Jackson 转换。 */
public final class AnnotatedTools {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private AnnotatedTools() {
    }

    public static List<Tool> from(Object bean) {
        return from(bean, bean.getClass());
    }

    /**
     * 扫描 type 上的注解方法、在 bean 上调用。用于框架代理对象（例如 Spring CGLIB 代理）：
     * 代理类重写的方法上没有注解，需要传入原始类型。
     */
    public static List<Tool> from(Object bean, Class<?> type) {
        List<Tool> tools = new ArrayList<>();
        Method[] methods = type.getMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName)); // 稳定顺序
        for (Method method : methods) {
            AgentTool annotation = method.getAnnotation(AgentTool.class);
            if (annotation != null) {
                tools.add(new MethodTool(bean, method, annotation));
            }
        }
        return tools;
    }

    /** 类型上是否有 {@link AgentTool} 方法。 */
    public static boolean hasTools(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(AgentTool.class)) {
                return true;
            }
        }
        return false;
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /** Java 类型 → JSON Schema。Optional&lt;T&gt; 按 T 处理；record 展开成带 properties 的对象；认不出的类型当作对象。 */
    static Map<String, Object> schemaOf(Type type) {
        Class<?> raw = rawClass(type);
        if (raw == Optional.class && type instanceof ParameterizedType) {
            return schemaOf(typeArgument(type));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        if (raw == String.class || raw == Character.class || raw == char.class) {
            schema.put("type", "string");
        } else if (raw == boolean.class || raw == Boolean.class) {
            schema.put("type", "boolean");
        } else if (raw == int.class || raw == long.class || raw == short.class || raw == Integer.class || raw == Long.class
                || raw == Short.class || raw == BigInteger.class) {
            schema.put("type", "integer");
        } else if (raw == double.class || raw == float.class || Number.class.isAssignableFrom(raw)) {
            schema.put("type", "number");
        } else if (raw.isEnum()) {
            schema.put("type", "string");
            schema.put("enum", Arrays.stream(raw.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList());
        } else if (raw.isArray()) {
            schema.put("type", "array");
            schema.put("items", schemaOf(raw.getComponentType()));
        } else if (Collection.class.isAssignableFrom(raw)) {
            schema.put("type", "array");
            if (type instanceof ParameterizedType) {
                schema.put("items", schemaOf(typeArgument(type)));
            }
        } else if (raw.isRecord()) {
            return recordSchema(raw);
        } else {
            schema.put("type", "object"); // Map 和其他类型
        }
        return schema;
    }

    /** record 的每个组件是一个属性；没有 @Param 或 @Param(required = true) 的是必填。 */
    private static Map<String, Object> recordSchema(Class<?> record) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            Map<String, Object> property = schemaOf(component.getGenericType());
            Param param = component.getAnnotation(Param.class);
            if (param != null && !param.value().isEmpty()) {
                property.put("description", param.value());
            }
            properties.put(component.getName(), property);
            if (param == null || param.required()) {
                required.add(component.getName());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> plain) {
            return plain;
        }
        return type instanceof ParameterizedType generic ? (Class<?>) generic.getRawType() : Object.class;
    }

    private static Type typeArgument(Type type) {
        return ((ParameterizedType) type).getActualTypeArguments()[0];
    }

    /** 一个带 {@link AgentTool} 的方法包装成的工具。参数按名字从模型给的 JSON 里取，用 Jackson 转成方法的参数类型。 */
    static final class MethodTool implements Tool {
        private final Object bean;
        private final Method method;
        private final AgentTool meta;
        private final String name;
        private final Map<String, Object> schema;
        /** 与方法参数一一对应；ToolContext 参数的位置是 null（调用时直接传上下文）。 */
        private final List<String> paramNames = new ArrayList<>();
        private final List<JavaType> paramTypes = new ArrayList<>();

        MethodTool(Object bean, Method method, AgentTool meta) {
            this.bean = bean;
            this.method = method;
            this.meta = meta;
            this.name = meta.name().isEmpty() ? snake(method.getName()) : meta.name();
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getType() == ToolContext.class) {
                    paramNames.add(null);
                    paramTypes.add(null);
                    continue;
                }
                Param param = parameter.getAnnotation(Param.class);
                // 参数名来自 @Param(name)，否则用编译后的参数名（需要 -parameters 编译选项）
                String paramName = param != null && !param.name().isEmpty() ? param.name() : parameter.getName();
                Map<String, Object> property = schemaOf(parameter.getParameterizedType());
                if (param != null && !param.value().isEmpty()) {
                    property.put("description", param.value());
                }
                properties.put(paramName, property);
                boolean optional = parameter.getType() == Optional.class || (param != null && !param.required());
                if (!optional) {
                    required.add(paramName);
                }
                paramNames.add(paramName);
                paramTypes.add(Json.MAPPER.getTypeFactory().constructType(parameter.getParameterizedType()));
            }
            Map<String, Object> objectSchema = new LinkedHashMap<>();
            objectSchema.put("type", "object");
            objectSchema.put("properties", properties);
            if (!required.isEmpty()) {
                objectSchema.put("required", required);
            }
            this.schema = objectSchema;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return meta.description();
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema;
        }

        @Override
        public boolean readOnly() {
            return meta.readOnly();
        }

        @Override
        public PermissionLevel defaultPermission() {
            return meta.readOnly() ? PermissionLevel.ALLOW : PermissionLevel.ASK;
        }

        /** timeoutSeconds：负数 = 不限时，0 = 默认 10 分钟，正数 = 指定秒数。 */
        @Override
        public Duration timeout() {
            if (meta.timeoutSeconds() < 0) {
                return null;
            }
            return meta.timeoutSeconds() == 0 ? DEFAULT_TIMEOUT : Duration.ofSeconds(meta.timeoutSeconds());
        }

        @Override
        public ToolOutput execute(Map<String, Object> arguments, ToolContext context) throws Exception {
            Object[] values = new Object[paramNames.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = paramNames.get(i) == null ? context : convert(arguments.get(paramNames.get(i)), paramTypes.get(i));
            }
            Object result;
            try {
                result = method.invoke(bean, values);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception thrown) {
                    throw thrown; // 让 ToolException 等原样到达引擎
                }
                throw e;
            }
            return toOutput(result);
        }

        /** 没传的参数：Optional → empty，基本类型 → 0/false，其他 → null。 */
        private static Object convert(Object value, JavaType type) {
            if (type.getRawClass() == Optional.class) {
                return Optional.ofNullable(value == null ? null : Json.MAPPER.convertValue(value, type.containedType(0)));
            }
            if (value == null) {
                return type.isPrimitive() ? Json.MAPPER.convertValue(0, type) : null;
            }
            return Json.MAPPER.convertValue(value, type);
        }
    }

    /** 方法返回值 → 工具结果：ToolOutput 原样；字符串当文本；内容块直接用；其他对象转成格式化的 JSON。 */
    @SuppressWarnings("unchecked")
    static ToolOutput toOutput(Object result) {
        if (result instanceof ToolOutput output) {
            return output;
        }
        if (result == null) {
            return ToolOutput.text("(no output)");
        }
        if (result instanceof String text) {
            return ToolOutput.text(text);
        }
        if (result instanceof ContentBlock block) {
            return new ToolOutput(List.of(block), false, Map.of());
        }
        if (result instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(ContentBlock.class::isInstance)) {
            return new ToolOutput((List<ContentBlock>) list, false, Map.of());
        }
        return ToolOutput.text(Json.pretty(result));
    }
}
