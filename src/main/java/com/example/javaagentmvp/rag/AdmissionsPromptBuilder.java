package com.example.javaagentmvp.rag;

/** Builds the augmented user prompt for multi-school admissions RAG (compatible with {@link com.example.javaagentmvp.chat.UserMessageTextCleaner}). */
final class AdmissionsPromptBuilder {

    static final String CONTEXT_MARKER = "Context information is below";

    private AdmissionsPromptBuilder() {
    }

    static String buildAugmentedUserMessage(String query, String groupedContext, String answerFormatTemplate) {
        String formatBlock = answerFormatTemplate == null || answerFormatTemplate.isBlank()
                ? ""
                : answerFormatTemplate.strip() + "\n\n";
        return query
                + "\n\n"
                + CONTEXT_MARKER
                + ", grouped by school:\n\n"
                + "---------------------\n\n"
                + groupedContext
                + "\n\n---------------------\n\n"
                + formatBlock
                + "Given the context and provided history information and not prior knowledge,\n"
                + "reply to the user comment. If the answer is not in the context, inform\n"
                + "the user that you can't answer the question.\n";
    }
}
