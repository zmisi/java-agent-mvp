package com.example.javaagentmvp.auth;

public enum UserStatus {
    ACTIVE,
    DISABLED;

    public static UserStatus fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return ACTIVE;
        }
        return UserStatus.valueOf(raw.strip().toUpperCase());
    }

    public String value() {
        return name().toLowerCase();
    }
}
