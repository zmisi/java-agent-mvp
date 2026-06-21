package com.example.javaagentmvp.chat.ui;

import java.nio.file.Files;
import java.nio.file.Path;

final class UniversityLogoUrls {

    private UniversityLogoUrls() {
    }

    static String resolve(UniversityProfile profile, Path logoDir) {
        if (profile == null) {
            return null;
        }
        String yxdm = profile.yxdm();
        if (UniversityTagDisplay.hasUsefulText(yxdm)) {
            Path localLogo = logoDir.resolve(yxdm.strip() + ".jpg").normalize();
            if (Files.isRegularFile(localLogo)) {
                return "/university-logos/" + yxdm.strip() + ".jpg";
            }
        }
        if (UniversityTagDisplay.hasUsefulText(profile.logoUrl())) {
            return profile.logoUrl().strip();
        }
        if (UniversityTagDisplay.hasUsefulText(yxdm)) {
            return "https://t1.chei.com.cn/common/xh/" + yxdm.strip() + ".jpg";
        }
        return null;
    }
}
