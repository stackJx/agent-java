package com.agentcore.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 会话详情里的图片默认不带 base64 数据：一张截图就有几 MB，前端列历史时通常用不着。
 * 去掉 {@code data}，换成 {@code data_omitted: true} 和大致的字节数；需要原图时请求 {@code ?include_images=true}。
 * 工具结果里嵌套的图片（例如 read_file 读的图）同样处理。
 */
final class ImageData {

    /** base64 每 4 个字符表示 3 个字节。 */
    private static final double BYTES_PER_BASE64_CHAR = 3.0 / 4.0;

    private ImageData() {
    }

    /** 原地去掉所有图片块的 data，返回同一个对象。 */
    static ObjectNode omitted(ObjectNode encodedMessage) {
        stripImageData(encodedMessage);
        return encodedMessage;
    }

    private static void stripImageData(JsonNode node) {
        if (node instanceof ObjectNode object && "image".equals(object.path("type").asText()) && object.hasNonNull("data")) {
            long approximateBytes = (long) (object.get("data").asText().length() * BYTES_PER_BASE64_CHAR);
            object.remove("data");
            object.put("data_omitted", true).put("bytes", approximateBytes);
        }
        node.forEach(ImageData::stripImageData); // 对象的每个字段值、数组的每个元素
    }
}
