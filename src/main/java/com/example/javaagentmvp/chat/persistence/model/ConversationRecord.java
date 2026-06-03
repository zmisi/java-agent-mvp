package com.example.javaagentmvp.chat.persistence.model;

import java.time.Instant;

public class ConversationRecord {

    private String id;

    private String title;

    private Instant createdAt;

    private Instant updatedAt;

    private Long userId;

    public ConversationRecord() {
    }

    public ConversationRecord(String id, String title, Instant createdAt, Instant updatedAt) {
        this(id, title, createdAt, updatedAt, null);
    }

    public ConversationRecord(String id, String title, Instant createdAt, Instant updatedAt, Long userId) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.userId = userId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
