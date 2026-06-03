package com.example.javaagentmvp.auth;

public enum UserRole {
    ADMIN,
    MEMBER,
    GUEST;

    public static UserRole fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return GUEST;
        }
        return UserRole.valueOf(raw.strip().toUpperCase());
    }

    public String value() {
        return name().toLowerCase();
    }
}
