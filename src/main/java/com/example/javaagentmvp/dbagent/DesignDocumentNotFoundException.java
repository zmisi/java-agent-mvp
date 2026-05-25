package com.example.javaagentmvp.dbagent;

public class DesignDocumentNotFoundException extends RuntimeException {

    public DesignDocumentNotFoundException(String relativePath) {
        super("design document not found: " + relativePath);
    }
}
