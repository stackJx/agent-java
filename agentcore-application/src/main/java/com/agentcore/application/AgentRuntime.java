package com.agentcore.application;

import com.agentcore.domain.agent.AgentEngine;
import com.agentcore.domain.memory.MemoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一个装配好的 Agent 运行时：应用服务 + 引擎 + 需要关闭的资源（MCP 子进程、HTTP 客户端等）。
 *
 * @param memory   长期记忆仓储，未启用时为 null
 * @param warnings 启动期的非致命问题（例如某个 MCP 服务器启动失败）
 */
public record AgentRuntime(AgentService service, AgentEngine engine, MemoryRepository memory, Map<String, String> warnings,
                           List<AutoCloseable> resources) implements AutoCloseable {

    @Override
    public void close() {
        List<AutoCloseable> list = new ArrayList<>(resources);
        java.util.Collections.reverse(list);
        for (AutoCloseable r : list) {
            try {
                r.close();
            } catch (Exception ignored) {
                // 关闭失败不影响其他资源
            }
        }
    }
}
