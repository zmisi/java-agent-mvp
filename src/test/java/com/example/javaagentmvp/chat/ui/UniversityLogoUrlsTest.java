package com.example.javaagentmvp.chat.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UniversityLogoUrlsTest {

    @Test
    void prefersLocalLogoWhenFileExists() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("uni-logos");
        Path logo = dir.resolve("10357.jpg");
        java.nio.file.Files.writeString(logo, "fake");

        UniversityProfile profile = new UniversityProfile(
                "安徽大学",
                "10357",
                "安徽",
                null,
                null,
                List.of(),
                "https://t1.chei.com.cn/common/xh/10357.jpg");

        assertThat(UniversityLogoUrls.resolve(profile, dir)).isEqualTo("/university-logos/10357.jpg");
    }

    @Test
    void fallsBackToRemoteLogoUrl() {
        UniversityProfile profile = new UniversityProfile(
                "安徽大学",
                "10357",
                "安徽",
                null,
                null,
                List.of(),
                "https://t1.chei.com.cn/common/xh/10357.jpg");

        assertThat(UniversityLogoUrls.resolve(profile, Path.of("/missing"))).isEqualTo(
                "https://t1.chei.com.cn/common/xh/10357.jpg");
    }
}
