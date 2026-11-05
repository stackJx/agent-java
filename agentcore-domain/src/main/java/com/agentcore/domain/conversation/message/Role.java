package com.agentcore.domain.conversation.message;

public enum Role {
    USER, ASSISTANT;

    public String wire() {
        return name().toLowerCase();
    }
}
