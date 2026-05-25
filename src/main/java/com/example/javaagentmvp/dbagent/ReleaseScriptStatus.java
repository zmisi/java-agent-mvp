package com.example.javaagentmvp.dbagent;

public enum ReleaseScriptStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    DEPLOYED;

    public boolean editable() {
        return this == DRAFT || this == REJECTED;
    }
}
