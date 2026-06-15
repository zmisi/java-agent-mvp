package com.example.javaagentmvp.admissionworkflow.service;

import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.UserRole;
import com.example.javaagentmvp.admissionworkflow.persistence.WorkflowRunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkflowAccessService {

    private final WorkflowRunRepository workflowRunRepository;

    public WorkflowAccessService(WorkflowRunRepository workflowRunRepository) {
        this.workflowRunRepository = workflowRunRepository;
    }

    public void requireAccess(String runId, AuthenticatedUser user) {
        if (user.role() == UserRole.ADMIN) {
            if (workflowRunRepository.findSummary(runId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow run not found");
            }
            return;
        }
        if (!workflowRunRepository.existsForUser(runId, user.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow run not found");
        }
    }
}
