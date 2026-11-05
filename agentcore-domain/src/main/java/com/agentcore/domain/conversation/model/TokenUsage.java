package com.agentcore.domain.conversation.model;

/** token 用量（值对象）。 */
public record TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens, int requests) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0, 0);

    public static TokenUsage of(long input, long output) {
        return new TokenUsage(input, output, 0, 0, 1);
    }

    /** 一次请求的完整输入规模（含缓存命中与写入部分）。 */
    public long totalInput() {
        return inputTokens + cacheReadTokens + cacheWriteTokens;
    }

    public boolean isEmpty() {
        return totalInput() == 0 && outputTokens == 0;
    }

    public TokenUsage plus(TokenUsage o) {
        if (o == null) {
            return this;
        }
        return new TokenUsage(inputTokens + o.inputTokens, outputTokens + o.outputTokens,
                cacheReadTokens + o.cacheReadTokens, cacheWriteTokens + o.cacheWriteTokens, requests + o.requests);
    }
}
