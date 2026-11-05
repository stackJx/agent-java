/**
 * 注解定义工具：在普通方法上加 {@link com.agentcore.infrastructure.tool.annotation.AgentTool}，
 * 参数加 {@link com.agentcore.infrastructure.tool.annotation.Param} 写说明。
 * {@link com.agentcore.infrastructure.tool.annotation.AnnotatedTools} 扫描这些方法生成工具：参数的 JSON Schema 由 Java 类型推断
 * （String、数字、布尔、枚举、List、record、Optional），调用时用 Jackson 把参数转换成方法的参数类型。
 */
package com.agentcore.infrastructure.tool.annotation;
