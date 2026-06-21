package com.example.javaagentmvp.chat.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UniversityTagDisplayTest {

    @Test
    void formatsKnownTagsAndSchoolLevel() {
        List<String> display = UniversityTagDisplay.format(
                List.of("211", "双一流"),
                "本科 研究生院");

        assertThat(display).containsExactly("本科", "研究生院", "211工程", "“双一流”建设高校");
    }

    @Test
    void formatsDepartmentWithPrefix() {
        assertThat(UniversityTagDisplay.formatDepartment("安徽省教育厅"))
                .isEqualTo("主管部门：安徽省教育厅");
        assertThat(UniversityTagDisplay.formatDepartment("主管部门：教育部"))
                .isEqualTo("主管部门：教育部");
    }
}
