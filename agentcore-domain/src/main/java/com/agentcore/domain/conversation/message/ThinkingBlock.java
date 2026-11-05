package com.agentcore.domain.conversation.message;

/**
 * 模型推理内容。
 *
 * @param provider     产生它的厂商（"anthropic" / "openai_compat"）；签名只对同一厂商有效
 * @param signature    厂商签名（Claude 回放思考块时必须原样带回）
 * @param redactedData 被加密的思考内容（Claude redacted_thinking）
 */
public record ThinkingBlock(String text, String signature, boolean redacted, String redactedData, String provider)
        implements ContentBlock {

    public static ThinkingBlock of(String text, String provider) {
        return new ThinkingBlock(text, null, false, null, provider);
    }

    @Override
    public String type() {
        return "thinking";
    }
}
