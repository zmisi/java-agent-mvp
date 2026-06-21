package com.example.javaagentmvp.chat.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UniversityProfileRepositoryTest {

    @Test
    void buildTagsFromLegacyFlags() {
        assertThat(UniversityProfileRepository.buildTags(true, true))
                .containsExactly("双一流", "研究生院");
        assertThat(UniversityProfileRepository.buildTags(false, false)).isEmpty();
    }

    @Test
    void mergeTagsPrefersDatabaseTagsAndFlags() {
        assertThat(UniversityProfileRepository.mergeTags(
                List.of("C9"),
                false,
                false,
                false,
                false))
                .containsExactly("C9");
    }

    @Test
    void mergeTagsUnionsDatabaseTagsAndFlags() {
        assertThat(UniversityProfileRepository.mergeTags(
                List.of("双一流"),
                false,
                true,
                false,
                false))
                .containsExactly("211", "双一流");
    }
}
