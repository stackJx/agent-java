/**
 * 领域层通用基础类。
 *
 * <ul>
 *   <li>{@link com.agentcore.domain.shared.AggregateRoot}：聚合根基类，收集领域事件，仓储保存时用 {@code pullEvents()} 取走；</li>
 *   <li>{@link com.agentcore.domain.shared.DomainEvent}：领域事件接口；</li>
 *   <li>{@link com.agentcore.domain.shared.CancellationToken} / {@link com.agentcore.domain.shared.CancelledException}：协作式取消；</li>
 *   <li>{@link com.agentcore.domain.shared.DomainException}：违反业务规则时抛出（例如工具调用没有一一回复）。</li>
 * </ul>
 */
package com.agentcore.domain.shared;
