package com.example.javaagentmvp.admissionworkflow.engine;

import java.util.List;
import java.util.Map;

public record WorkflowDefinition(String workflowType, List<WorkflowNode> nodes) {
}
