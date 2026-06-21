package com.example.javaagentmvp.chat.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UniversityYxdmTagRegistryTest {

    @Test
    void loads211TagsForAnhuiUniversities() {
        UniversityYxdmTagRegistry registry = new UniversityYxdmTagRegistry();
        registry.load();

        assertThat(registry.tagsForYxdm("10357")).contains("211");
        assertThat(registry.tagsForYxdm("10359")).contains("211", "机械四小龙");
    }

    @Test
    void mergeTagsKeeps211WhenDatabaseOnlyHasDoubleFirstClass() {
        List<String> tags = UniversityYxdmTagRegistry.unionTags(
                UniversityProfileRepository.buildTags(true, false),
                List.of("211"));

        assertThat(tags).containsExactly("211", "双一流");
    }
}
