package com.agentcore.domain.conversation;

import java.util.Objects;

public record TextBlock(String text) implements ContentBlock {
    public TextBlock {
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String type() {
        return "text";
    }
}
