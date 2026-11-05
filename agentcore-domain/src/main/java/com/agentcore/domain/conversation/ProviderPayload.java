package com.agentcore.domain.conversation;

/**
 * 厂商原始内容（JSON 字符串）。助手消息回放给同一厂商时原样发送，
 * 保证思考块签名、服务端回退标记等不丢失，历史保持「只追加、不改写」。
 */
public record ProviderPayload(String provider, String model, String rawContentJson) {
}
