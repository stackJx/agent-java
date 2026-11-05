package com.agentcore.infrastructure.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 工具参数说明。参数名取自 name()，为空时取编译期参数名（需要 javac -parameters）。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Param {
    /** 参数说明。 */
    String value() default "";

    String name() default "";

    boolean required() default true;
}
