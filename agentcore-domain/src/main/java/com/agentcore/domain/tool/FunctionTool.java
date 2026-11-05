package com.agentcore.domain.tool;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 用 lambda 定义工具：
 * <pre>
 * Tool weather = FunctionTool.builder("get_weather")
 *     .description("查询城市天气")
 *     .schema(Schemas.object().string("city", "城市名", true).build())
 *     .readOnly(true)
 *     .handler((args, ctx) -&gt; ToolOutput.text("晴 25°C"))
 *     .build();
 * </pre>
 */
public final class FunctionTool implements Tool {

    @FunctionalInterface
    public interface Handler {
        ToolOutput handle(Map<String, Object> arguments, ToolContext context) throws Exception;
    }

    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final boolean readOnly;
    private final Boolean concurrencySafe;
    private final PermissionLevel permission;
    private final Duration timeout;
    private final int maxOutputChars;
    private final Handler handler;

    private FunctionTool(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.schema = b.schema;
        this.readOnly = b.readOnly;
        this.concurrencySafe = b.concurrencySafe;
        this.permission = b.permission;
        this.timeout = b.timeout;
        this.maxOutputChars = b.maxOutputChars;
        this.handler = Objects.requireNonNull(b.handler, "handler");
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return schema;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public boolean concurrencySafe() {
        return concurrencySafe != null ? concurrencySafe : readOnly;
    }

    @Override
    public PermissionLevel defaultPermission() {
        return permission != null ? permission : Tool.super.defaultPermission();
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public int maxOutputChars() {
        return maxOutputChars;
    }

    @Override
    public ToolOutput execute(Map<String, Object> arguments, ToolContext context) throws Exception {
        return handler.handle(arguments, context);
    }

    public static final class Builder {
        private final String name;
        private String description = "";
        private Map<String, Object> schema = Schemas.object().build();
        private boolean readOnly;
        private Boolean concurrencySafe;
        private PermissionLevel permission;
        private Duration timeout = Duration.ofMinutes(10);
        private int maxOutputChars = 30_000;
        private Handler handler;

        private Builder(String name) {
            this.name = name;
        }

        public Builder description(String d) {
            this.description = d;
            return this;
        }

        public Builder schema(Map<String, Object> s) {
            this.schema = s;
            return this;
        }

        public Builder readOnly(boolean r) {
            this.readOnly = r;
            return this;
        }

        public Builder concurrencySafe(boolean c) {
            this.concurrencySafe = c;
            return this;
        }

        public Builder permission(PermissionLevel p) {
            this.permission = p;
            return this;
        }

        public Builder timeout(Duration t) {
            this.timeout = t;
            return this;
        }

        public Builder maxOutputChars(int n) {
            this.maxOutputChars = n;
            return this;
        }

        public Builder handler(Handler h) {
            this.handler = h;
            return this;
        }

        public FunctionTool build() {
            return new FunctionTool(this);
        }
    }
}
