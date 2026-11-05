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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 扫描对象上带 {@link AgentTool} 的方法，生成 {@link Tool}：Schema 由 Java 类型推断，参数用 Jackson 转换。 */
public final class AnnotatedTools {

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
        List<Tool> out = new ArrayList<>();
        Method[] methods = type.getMethods();
        Arrays.sort(methods, (a, b) -> a.getName().compareTo(b.getName())); // 稳定顺序
        for (Method m : methods) {
            AgentTool at = m.getAnnotation(AgentTool.class);
            if (at != null) {
                out.add(new MethodTool(bean, m, at));
            }
        }
        return out;
    }

    /** 类型上是否有 {@link AgentTool} 方法。 */
    public static boolean hasTools(Class<?> type) {
        for (Method m : type.getMethods()) {
            if (m.isAnnotationPresent(AgentTool.class)) {
                return true;
            }
        }
        return false;
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /** Java 类型 → JSON Schema。 */
    static Map<String, Object> schemaOf(Type type) {
        Class<?> raw = type instanceof Class<?> c ? c : type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : Object.class;
        Map<String, Object> s = new LinkedHashMap<>();
        if (raw == String.class || raw == Character.class || raw == char.class) {
            s.put("type", "string");
        } else if (raw == boolean.class || raw == Boolean.class) {
            s.put("type", "boolean");
        } else if (raw == int.class || raw == long.class || raw == short.class || raw == Integer.class || raw == Long.class
                || raw == Short.class || raw == java.math.BigInteger.class) {
            s.put("type", "integer");
        } else if (raw == double.class || raw == float.class || Number.class.isAssignableFrom(raw)) {
            s.put("type", "number");
        } else if (raw.isEnum()) {
            s.put("type", "string");
            s.put("enum", Arrays.stream(raw.getEnumConstants()).map(e -> ((Enum<?>) e).name()).toList());
        } else if (raw.isArray()) {
            s.put("type", "array");
            s.put("items", schemaOf(raw.getComponentType()));
        } else if (Collection.class.isAssignableFrom(raw)) {
            s.put("type", "array");
            if (type instanceof ParameterizedType p) {
                s.put("items", schemaOf(p.getActualTypeArguments()[0]));
            }
        } else if (Map.class.isAssignableFrom(raw)) {
            s.put("type", "object");
        } else if (raw == Optional.class && type instanceof ParameterizedType p) {
            return schemaOf(p.getActualTypeArguments()[0]);
        } else if (raw.isRecord()) {
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent rc : raw.getRecordComponents()) {
                Map<String, Object> ps = schemaOf(rc.getGenericType());
                Param p = rc.getAnnotation(Param.class);
                if (p != null && !p.value().isEmpty()) {
                    ps.put("description", p.value());
                }
                props.put(rc.getName(), ps);
                if (p == null || p.required()) {
                    required.add(rc.getName());
                }
            }
            s.put("type", "object");
            s.put("properties", props);
            s.put("required", required);
        } else {
            s.put("type", "object");
        }
        return s;
    }

    static final class MethodTool implements Tool {
        private final Object bean;
        private final Method method;
        private final AgentTool meta;
        private final String name;
        private final Map<String, Object> schema;
        private final List<String> paramNames = new ArrayList<>();
        private final List<JavaType> paramTypes = new ArrayList<>();

        MethodTool(Object bean, Method method, AgentTool meta) {
            this.bean = bean;
            this.method = method;
            this.meta = meta;
            this.name = meta.name().isEmpty() ? snake(method.getName()) : meta.name();
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Parameter p : method.getParameters()) {
                if (p.getType() == ToolContext.class) {
                    paramNames.add(null);
                    paramTypes.add(null);
                    continue;
                }
                Param pa = p.getAnnotation(Param.class);
                String pn = pa != null && !pa.name().isEmpty() ? pa.name() : p.getName();
                Map<String, Object> ps = schemaOf(p.getParameterizedType());
                if (pa != null && !pa.value().isEmpty()) {
                    ps.put("description", pa.value());
                }
                props.put(pn, ps);
                boolean optional = p.getType() == Optional.class || (pa != null && !pa.required());
                if (!optional) {
                    required.add(pn);
                }
                paramNames.add(pn);
                paramTypes.add(Json.MAPPER.getTypeFactory().constructType(p.getParameterizedType()));
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "object");
            s.put("properties", props);
            if (!required.isEmpty()) {
                s.put("required", required);
            }
            this.schema = s;
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

        @Override
        public Duration timeout() {
            return meta.timeoutSeconds() < 0 ? null : meta.timeoutSeconds() == 0 ? Duration.ofMinutes(10)
                    : Duration.ofSeconds(meta.timeoutSeconds());
        }

        @Override
        public ToolOutput execute(Map<String, Object> arguments, ToolContext context) throws Exception {
            Object[] args = new Object[paramNames.size()];
            for (int i = 0; i < args.length; i++) {
                if (paramNames.get(i) == null) {
                    args[i] = context;
                    continue;
                }
                Object v = arguments.get(paramNames.get(i));
                JavaType jt = paramTypes.get(i);
                if (jt.getRawClass() == Optional.class) {
                    args[i] = Optional.ofNullable(v == null ? null : Json.MAPPER.convertValue(v, jt.containedType(0)));
                } else {
                    args[i] = v == null ? (jt.isPrimitive() ? Json.MAPPER.convertValue(0, jt) : null) : Json.MAPPER.convertValue(v, jt);
                }
            }
            Object result;
            try {
                result = method.invoke(bean, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception ex) {
                    throw ex;
                }
                throw e;
            }
            return toOutput(result);
        }
    }

    @SuppressWarnings("unchecked")
    static ToolOutput toOutput(Object result) {
        if (result instanceof ToolOutput o) {
            return o;
        }
        if (result == null) {
            return ToolOutput.text("(no output)");
        }
        if (result instanceof String s) {
            return ToolOutput.text(s);
        }
        if (result instanceof ContentBlock b) {
            return new ToolOutput(List.of(b), false, Map.of());
        }
        if (result instanceof List<?> l && !l.isEmpty() && l.stream().allMatch(ContentBlock.class::isInstance)) {
            return new ToolOutput((List<ContentBlock>) l, false, Map.of());
        }
        return ToolOutput.text(Json.pretty(result));
    }
}
