package com.example.javaagentmvp.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMessageTextCleanerTest {

    @Test
    void stripsQuestionAnswerAdvisorContext() {
        String augmented = """
                谈谈 RAG

                Context information is below, surrounded by ---------------------

                ---------------------
                # RAG Basics
                RAG means Retrieval-Augmented Generation.
                ---------------------

                Given the context and provided history information and not prior knowledge,
                reply to the user comment.""";

        assertEquals("谈谈 RAG", UserMessageTextCleaner.clean(augmented));
    }

    @Test
    void leavesPlainUserMessageUntouched() {
        assertEquals("list all tables", UserMessageTextCleaner.clean("list all tables"));
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals("", UserMessageTextCleaner.clean(null));
        assertEquals("  ", UserMessageTextCleaner.clean("  "));
    }
}
