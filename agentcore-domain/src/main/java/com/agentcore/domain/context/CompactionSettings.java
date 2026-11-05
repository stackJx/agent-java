package com.agentcore.domain.context;

/**
 * 压缩策略参数。
 *
 * @param threshold         估算 token 超过 contextWindow * threshold 时触发压缩
 * @param maxTokens         绝对上限（可选，&lt;=0 表示不限）：窗口很大时也在这里压缩，控制成本
 * @param summaryMaxTokens  摘要请求的最大输出
 * @param transcriptBudget  摘要输入的估算 token 上限（超出时保留头部 20% + 尾部 80%）
 */
public record CompactionSettings(double threshold, long maxTokens, int summaryMaxTokens, int transcriptBudget) {

    public static CompactionSettings defaults() {
        return new CompactionSettings(0.75, 0, 8000, 120_000);
    }

    public long limitFor(int contextWindow) {
        long limit = (long) (contextWindow * threshold);
        return maxTokens > 0 ? Math.min(limit, maxTokens) : limit;
    }
}
