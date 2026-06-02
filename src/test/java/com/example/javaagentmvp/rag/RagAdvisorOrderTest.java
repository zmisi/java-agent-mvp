package com.example.javaagentmvp.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagAdvisorOrderTest {

    @Test
    void ragAdvisorsRunAfterChatMemory() {
        int memoryOrder = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
        assertTrue(RagAdvisorOrder.FLOW_START > memoryOrder);
        assertTrue(RagAdvisorOrder.ADMISSIONS_FORMAT > RagAdvisorOrder.FLOW_START);
        assertTrue(RagAdvisorOrder.CONDITIONAL_QA > RagAdvisorOrder.ADMISSIONS_FORMAT);
    }
}
