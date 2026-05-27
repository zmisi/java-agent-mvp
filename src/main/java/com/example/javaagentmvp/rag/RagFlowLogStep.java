package com.example.javaagentmvp.rag;

/** Log step tags for the RAG chat pipeline (grep-friendly, self-describing). */
public final class RagFlowLogStep {

    public static final String QUESTION = "[question]";

    public static final String RETRIEVE = "[retrieve]";

    /** Multi-school admissions: group chunks by school and apply answer-format template. */
    public static final String FORMAT = "[format]";

    public static final String PROMPT = "[prompt]";

    public static final String ANSWER = "[answer]";

    private RagFlowLogStep() {
    }
}
