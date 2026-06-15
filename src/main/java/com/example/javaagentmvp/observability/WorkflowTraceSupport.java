package com.example.javaagentmvp.observability;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.nodes.IntentClassifyNode;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

@Component
public class WorkflowTraceSupport {

    public static final String MDC_RUN_ID = "workflowRunId";

    private final ObservationRegistry observationRegistry;

    public WorkflowTraceSupport(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    public <T> T observeWorkflowRun(String runId, String workflowType, Supplier<T> supplier) {
        Observation observation = Observation.createNotStarted("agent.workflow.run", observationRegistry)
                .lowCardinalityKeyValue("workflow.type", workflowType)
                .highCardinalityKeyValue("workflow.run_id", runId);
        return observation.observe(() -> withRunId(runId, supplier));
    }

    public <T> T observeNode(
            String runId,
            String nodeName,
            Map<String, Object> contextSnapshot,
            Supplier<T> supplier) {
        String intent = resolveIntent(contextSnapshot);
        Observation observation = Observation.createNotStarted("agent.workflow.node." + nodeName, observationRegistry)
                .lowCardinalityKeyValue("workflow.node", nodeName)
                .highCardinalityKeyValue("workflow.run_id", runId);
        if (intent != null) {
            observation.highCardinalityKeyValue("workflow.intent", intent);
        }
        return observation.observe(() -> withRunId(runId, supplier));
    }

    public void withRunId(String runId, Runnable runnable) {
        withRunId(runId, () -> {
            runnable.run();
            return null;
        });
    }

    public <T> T withRunId(String runId, Supplier<T> supplier) {
        String previous = MDC.get(MDC_RUN_ID);
        MDC.put(MDC_RUN_ID, runId);
        try {
            return supplier.get();
        }
        finally {
            if (previous == null) {
                MDC.remove(MDC_RUN_ID);
            }
            else {
                MDC.put(MDC_RUN_ID, previous);
            }
        }
    }

    private static String resolveIntent(Map<String, Object> contextSnapshot) {
        if (contextSnapshot == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) contextSnapshot.get("state");
        if (state == null) {
            return null;
        }
        Object intent = state.get(IntentClassifyNode.KEY_INTENT);
        if (intent instanceof AdmissionIntent admissionIntent) {
            return admissionIntent.name();
        }
        if (intent != null) {
            return intent.toString();
        }
        return null;
    }
}
