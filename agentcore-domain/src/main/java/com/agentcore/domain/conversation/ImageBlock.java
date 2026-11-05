package com.agentcore.domain.conversation;

/** 图片：base64 数据（不含 data: 前缀）或 URL 二选一。 */
public record ImageBlock(String mediaType, String data, String url) implements ContentBlock {

    public static ImageBlock base64(String mediaType, String data) {
        return new ImageBlock(mediaType, data, null);
    }

    public static ImageBlock url(String url) {
        return new ImageBlock(null, null, url);
    }

    @Override
    public String type() {
        return "image";
    }
}
