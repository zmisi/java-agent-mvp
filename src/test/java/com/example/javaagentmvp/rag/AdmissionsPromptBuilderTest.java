package com.example.javaagentmvp.rag;

import com.example.javaagentmvp.chat.UserMessageTextCleaner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionsPromptBuilderTest {

    @Test
    void includesGroupedContextAndFormatInstructions() {
        String augmented = AdmissionsPromptBuilder.buildAugmentedUserMessage(
                "630 分报考什么专业",
                "## 合肥工业大学\n\nscore data",
                "按学校分组回答");

        assertTrue(augmented.contains("630 分报考什么专业"));
        assertTrue(augmented.contains("grouped by school"));
        assertTrue(augmented.contains("## 合肥工业大学"));
        assertTrue(augmented.contains("按学校分组回答"));
    }

    @Test
    void cleanerStripsAugmentedContextBackToOriginalQuestion() {
        String augmented = AdmissionsPromptBuilder.buildAugmentedUserMessage(
                "630 分报考什么专业", "ctx", "fmt");
        assertEquals("630 分报考什么专业", UserMessageTextCleaner.clean(augmented));
    }
}
