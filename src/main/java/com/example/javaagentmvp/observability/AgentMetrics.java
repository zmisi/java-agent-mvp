package com.example.javaagentmvp.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AgentMetrics {

    private final MeterRegistry meterRegistry;

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordWorkflowRun(String workflowType, String status) {
        Counter.builder("agent.workflow.run")
                .tag("workflow_type", workflowType)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    public void recordWorkflowNode(String node, String status, long durationMs) {
        Timer.builder("agent.workflow.node")
                .tag("node", node)
                .tag("status", status)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordToolCall(String tool, long durationMs) {
        Timer.builder("agent.tool.call")
                .tag("tool", tool)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordRagRetrieve(String mode, long durationMs, int hitCount) {
        Timer.builder("agent.rag.retrieve")
                .tag("mode", mode)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        DistributionSummary.builder("agent.rag.retrieve.hits")
                .register(meterRegistry)
                .record(hitCount);
    }

    public void recordLlmCall(String scope, long durationMs) {
        Timer.builder("agent.llm.call")
                .tag("scope", scope)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
