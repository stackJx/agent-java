package com.agentcore.domain.agent;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 引擎运行参数。
 *
 * @param maxRetries         模型可重试错误（限流 / 5xx / 网络）的重试次数（SDK 自身也会先重试）
 * @param maxMalformedRetries 模型输出无法解析时重发本轮的次数
 * @param maxDepth           子 Agent 最大嵌套深度
 */
public record EngineSettings(Path cwd, int maxTurns, int maxToolConcurrency, int maxRetries, Duration retryBaseDelay,
                             int maxMalformedRetries, int maxDepth) {

    public static EngineSettings defaults(Path cwd) {
        return new EngineSettings(cwd, 100, 8, 3, Duration.ofSeconds(2), 2, 1);
    }

    public EngineSettings withMaxTurns(int n) {
        return new EngineSettings(cwd, n, maxToolConcurrency, maxRetries, retryBaseDelay, maxMalformedRetries, maxDepth);
    }

    public EngineSettings withRetryBaseDelay(Duration d) {
        return new EngineSettings(cwd, maxTurns, maxToolConcurrency, maxRetries, d, maxMalformedRetries, maxDepth);
    }
}
