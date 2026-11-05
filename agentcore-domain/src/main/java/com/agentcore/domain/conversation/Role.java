package com.agentcore.domain.conversation;

public enum Role {
    USER, ASSISTANT;

    public String wire() {
        return name().toLowerCase();
    }
}
