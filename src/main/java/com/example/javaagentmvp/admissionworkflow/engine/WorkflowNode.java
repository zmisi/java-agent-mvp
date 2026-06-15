package com.example.javaagentmvp.admissionworkflow.engine;

public interface WorkflowNode {

    String name();

    WorkflowNodeResult execute(WorkflowContext context);
}
