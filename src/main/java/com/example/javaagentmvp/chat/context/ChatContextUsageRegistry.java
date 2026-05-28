package com.example.javaagentmvp.chat.context;

import org.springframework.stereotype.Component;

/**
 * Holds per-request {@link ContextUsageResponse} from {@link ChatContextUsageAdvisor}
 * for the HTTP thread handling {@code POST /api/conversations/{id}/chat}.
 */
@Component
public final class ChatContextUsageRegistry {

    private static final ThreadLocal<ContextUsageResponse> LAST = new ThreadLocal<>();

    public void publish(ContextUsageResponse snapshot) {
        LAST.set(snapshot);
    }

    /**
     * Returns the snapshot for this thread and clears the slot (if any).
     */
    public ContextUsageResponse consume() {
        try {
            return LAST.get();
        }
        finally {
            LAST.remove();
        }
    }
}
