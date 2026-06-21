package com.example.javaagentmvp.chat.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.university-profile")
public record UniversityProfileProperties(
        boolean enabled,
        String logoDir) {

    public UniversityProfileProperties {
        if (logoDir == null || logoDir.isBlank()) {
            logoDir = "./data/chsi/universities/logos";
        }
    }

    public static UniversityProfileProperties defaults() {
        return new UniversityProfileProperties(true, "./data/chsi/universities/logos");
    }
}
