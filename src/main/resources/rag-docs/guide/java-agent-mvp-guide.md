# Java Agent MVP RAG Guide

This project is a Spring Boot application named `java-agent-mvp`. It uses
Spring AI, DashScope Qwen, a Web UI, PostgreSQL-backed chat memory, MCP tools
for the database agent, and an integrated RAG knowledge base.

RAG is built into the main chat endpoint:

`POST /api/conversations/{conversationId}/chat`

Request body:

```json
{"message":"RAG 和微调有什么区别？"}
```

Response body:

```json
{
  "assistant": "Answer text from the model",
  "sources": [
    {"title": "rag-basics.md", "source": "rag-basics.md", "snippet": "Retrieved text"}
  ]
}
```

To run locally:

1. Set `DASHSCOPE_API_KEY`.
2. Start the Spring Boot app with `mvn spring-boot:run`.
3. Open `http://localhost:8080/`, create or select a chat.
4. Ask questions such as `RAG 和微调有什么区别？` or database questions.

The knowledge base uses `SimpleVectorStore`, so indexed documents live in memory
and are rebuilt at application startup. Set `app.rag.enabled=false` to disable
RAG and use the database agent only.
