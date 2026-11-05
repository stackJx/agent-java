/**
 * 文件和内存存储。
 *
 * <ul>
 *   <li>{@link com.agentcore.infrastructure.persistence.JsonlConversationRepository}：默认的会话存储，每个会话一个 JSONL 文件，一行一个领域事件，只追加；</li>
 *   <li>{@link com.agentcore.infrastructure.persistence.InMemoryConversationRepository}：内存存储，测试和 {@code --no-session} 用；</li>
 *   <li>{@link com.agentcore.infrastructure.persistence.FileMemoryRepository}：长期记忆，每条一个带 frontmatter 的 Markdown 文件；</li>
 *   <li>{@link com.agentcore.infrastructure.persistence.FileSkillCatalog}：扫描 {@code SKILL.md} 加载技能。</li>
 * </ul>
 * 数据库存储在 {@code jdbc} 子包。详见 {@code docs/06-persistence.md}。
 */
package com.agentcore.infrastructure.persistence;
