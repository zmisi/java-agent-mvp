package com.example.javaagentmvp.chat;

/**
 * Strips {@link org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor}
 * prompt augmentation from persisted or displayed user messages.
 */
public final class UserMessageTextCleaner {

    private static final String RAG_CONTEXT_MARKER = "Context information is below";

    private UserMessageTextCleaner() {
    }

    public static String clean(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        int markerIndex = text.indexOf(RAG_CONTEXT_MARKER);
        if (markerIndex < 0) {
            return text;
        }
        String question = text.substring(0, markerIndex).strip();
        return question.isEmpty() ? text : question;
    }
}
