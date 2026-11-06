/**
 * 小工具：通配符匹配 {@link com.agentcore.domain.shared.util.GlobPattern}、文本截断 {@link com.agentcore.domain.shared.util.Texts}、
 * 有容量上限的 LRU 缓存 {@link com.agentcore.domain.shared.util.BoundedLruCache}（按会话 ID 存放的内存状态用它，避免只增不减）、
 * 文件路径解析 {@link com.agentcore.domain.shared.util.FilePaths}（工具参数里的路径 → 绝对路径、跟随符号链接后的真实路径）。
 */
package com.agentcore.domain.shared.util;
