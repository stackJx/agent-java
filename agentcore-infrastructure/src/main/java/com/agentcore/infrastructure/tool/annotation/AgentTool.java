package com.agentcore.infrastructure.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个方法声明为工具。参数类型自动推断为 JSON Schema，参数说明用 {@link Param}。
 * <pre>
 * public class OrderTools {
 *     &#64;AgentTool(description = "查询订单状态", readOnly = true)
 *     public Order getOrder(&#64;Param("订单号，例如 A1001") String orderId) { ... }
 * }
 * registry.registerAll(AnnotatedTools.from(new OrderTools()));
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {
    /** 工具名，默认把方法名转成 snake_case。 */
    String name() default "";

    String description();

    boolean readOnly() default false;

    /** 超时秒数，0 表示默认（10 分钟），-1 表示不限。 */
    int timeoutSeconds() default 0;
}
