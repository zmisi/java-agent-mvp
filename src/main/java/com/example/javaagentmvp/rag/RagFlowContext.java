package com.example.javaagentmvp.rag;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.UUID;

/** Correlates log lines and retrieved sources for a single chat request that uses RAG. */
public final class RagFlowContext {

    private static final ThreadLocal<String> FLOW_ID = new ThreadLocal<>();

    private static final ThreadLocal<List<RagSource>> SOURCES = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> SKIPPED = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> MULTI_SCHOOL_ADMISSIONS = new ThreadLocal<>();

    private static final ThreadLocal<List<Document>> PRE_RETRIEVED_DOCUMENTS = new ThreadLocal<>();

    private RagFlowContext() {
    }

    public static void start() {
        if (FLOW_ID.get() != null) {
            return;
        }
        FLOW_ID.set(UUID.randomUUID().toString().substring(0, 8));
        SKIPPED.set(Boolean.FALSE);
    }

    public static String flowId() {
        String id = FLOW_ID.get();
        return id != null ? id : "--------";
    }

    public static void skip(String reason) {
        SKIPPED.set(Boolean.TRUE);
        setSources(List.of());
        clearMultiSchoolAdmissions();
    }

    public static void beginMultiSchoolAdmissions(List<Document> documents) {
        MULTI_SCHOOL_ADMISSIONS.set(Boolean.TRUE);
        PRE_RETRIEVED_DOCUMENTS.set(documents == null ? List.of() : List.copyOf(documents));
    }

    public static boolean isMultiSchoolAdmissions() {
        return Boolean.TRUE.equals(MULTI_SCHOOL_ADMISSIONS.get());
    }

    public static List<Document> preRetrievedDocuments() {
        List<Document> documents = PRE_RETRIEVED_DOCUMENTS.get();
        return documents == null ? List.of() : documents;
    }

    private static void clearMultiSchoolAdmissions() {
        MULTI_SCHOOL_ADMISSIONS.remove();
        PRE_RETRIEVED_DOCUMENTS.remove();
    }

    public static boolean isSkipped() {
        return Boolean.TRUE.equals(SKIPPED.get());
    }

    public static void setSources(List<RagSource> sources) {
        SOURCES.set(sources == null ? List.of() : List.copyOf(sources));
    }

    public static List<RagSource> sources() {
        List<RagSource> sources = SOURCES.get();
        return sources == null ? List.of() : sources;
    }

    public static void clear() {
        FLOW_ID.remove();
        SOURCES.remove();
        SKIPPED.remove();
        clearMultiSchoolAdmissions();
    }
}
