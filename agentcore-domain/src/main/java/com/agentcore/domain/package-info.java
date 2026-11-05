/**
 * 领域层：Agent 的业务规则本身，只依赖 {@code java.*}，不引入任何框架。
 *
 * <p>按业务模块分包，每个模块内部再按 DDD 构件类型分子包，新增代码按类型放：
 * <ul>
 *   <li>{@code model}：聚合根、实体、值对象（{@code conversation.message} 是消息这一组值对象）；</li>
 *   <li>{@code event}：领域事件 / 对外事件；</li>
 *   <li>{@code service}：领域服务（无状态的业务规则，跨多个对象）。默认直接写类；只有业务方会想换成自己规则的才抽接口，
 *       例如 {@code permission.service.PermissionPolicy}（接口）+ {@code RulePermissionPolicy}（默认实现）；</li>
 *   <li>{@code repository}：持久化或读取数据的端口（由基础设施层实现）；</li>
 *   <li>{@code gateway}：调用外部系统或人的端口（模型、审批、提问，由基础设施层或接口层实现）；</li>
 *   <li>{@code exception}：本模块的异常；其他按职责命名（{@code tool.schema}、{@code agent.hook}、{@code shared.cancel}）。</li>
 * </ul>
 *
 * <p>模块：
 * <ul>
 *   <li>{@code agent}：Agent 主循环（{@code agent.service.AgentEngine}）；</li>
 *   <li>{@code conversation}：会话聚合、消息、领域事件；</li>
 *   <li>{@code context}：上下文长度估算与压缩；</li>
 *   <li>{@code llm}：和大模型对话的端口与请求 / 响应；</li>
 *   <li>{@code tool}：工具定义、注册、参数校验；</li>
 *   <li>{@code permission}：权限策略与审批；</li>
 *   <li>{@code memory} / {@code skill}：长期记忆、技能；</li>
 *   <li>{@code shared}：各模块共用的基础类型。</li>
 * </ul>
 * 详见 docs/01-architecture.md 第 3 节。
 */
package com.agentcore.domain;
