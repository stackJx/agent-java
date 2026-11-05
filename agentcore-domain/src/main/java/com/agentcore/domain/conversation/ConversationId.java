package com.agentcore.domain.conversation;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;

/** 会话标识（值对象）。形如 20261104-181408-ab12cd，按时间可排序。 */
public record ConversationId(String value) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public ConversationId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException("invalid conversation id: " + value);
        }
    }

    public static ConversationId newId() {
        byte[] b = new byte[3];
        RANDOM.nextBytes(b);
        return new ConversationId(LocalDateTime.now().format(FMT) + "-" + HexFormat.of().formatHex(b));
    }

    public static ConversationId of(String value) {
        return new ConversationId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
