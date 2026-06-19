package com.example.javaagentmvp.admissionworkflow.format;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RankSubjectGroupResolverTest {

    @Test
    void omitsSubjectGroupForComprehensiveProvinces() {
        assertThat(RankSubjectGroupResolver.rankSubjectGroupForProvince("浙江", "物理类")).isNull();
        assertThat(RankSubjectGroupResolver.rankSubjectGroupForProvince("上海", "历史类")).isNull();
    }

    @Test
    void keepsSubjectGroupForSplitProvinces() {
        assertThat(RankSubjectGroupResolver.rankSubjectGroupForProvince("江苏", "物理类")).isEqualTo("物理类");
        assertThat(RankSubjectGroupResolver.rankSubjectGroupForProvince("安徽", "历史类")).isEqualTo("历史类");
    }
}
