package com.example.javaagentmvp.admissionworkflow.engine;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkflowContext {

    private final String runId;
    private final String conversationId;
    private final String inputMessage;
    private final Map<String, Object> state;

    public WorkflowContext(String runId, String inputMessage) {
        this(runId, null, inputMessage);
    }

    public WorkflowContext(String runId, String conversationId, String inputMessage) {
        this.runId = runId;
        this.conversationId = conversationId;
        this.inputMessage = inputMessage;
        this.state = new LinkedHashMap<>();
    }

    public String runId() {
        return runId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String inputMessage() {
        return inputMessage;
    }

    public void put(String key, Object value) {
        state.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = state.get(key);
        if (value == null) {
            return null;
        }
        return type.cast(value);
    }

    public Object get(String key) {
        return state.get(key);
    }

    public Map<String, Object> snapshot() {
        return Map.copyOf(state);
    }

    public Map<String, Object> nodeInputSnapshot() {
        return Map.of("inputMessage", inputMessage, "state", snapshot());
    }
}
