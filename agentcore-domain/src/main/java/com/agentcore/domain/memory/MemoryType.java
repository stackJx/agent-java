package com.agentcore.domain.memory;

public enum MemoryType {
    USER, FEEDBACK, PROJECT, REFERENCE, NOTE;

    public static MemoryType parse(String s) {
        try {
            return s == null ? NOTE : valueOf(s.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NOTE;
        }
    }

    public String wire() {
        return name().toLowerCase();
    }
}
