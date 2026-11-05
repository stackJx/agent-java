/**
 * 领域层测试共用的测试替身（不在主源码里，也不会打进产物）。
 *
 * <p>{@link com.agentcore.domain.testsupport.FakeModel} 是脚本化的 {@code ModelGateway}：
 * 预先排好每次调用模型的回复，就能在完全不联网的情况下驱动 {@code AgentEngine} 的主循环。
 */
package com.agentcore.domain.testsupport;
