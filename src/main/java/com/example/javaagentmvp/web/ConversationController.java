package com.example.javaagentmvp.web;

import com.example.javaagentmvp.chat.AgentConversationRepository;
import com.example.javaagentmvp.chat.PostgresChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final AgentConversationRepository conversationRepository;

    private final PostgresChatMemory postgresChatMemory;

    public ConversationController(
            AgentConversationRepository conversationRepository,
            PostgresChatMemory postgresChatMemory) {
        this.conversationRepository = conversationRepository;
        this.postgresChatMemory = postgresChatMemory;
    }

    @GetMapping
    public List<ConversationSummaryDto> list() {
        return conversationRepository.listSummaries().stream()
                .map(s -> new ConversationSummaryDto(s.id(), s.title(), s.createdAt(), s.updatedAt()))
                .toList();
    }

    @PostMapping
    public ConversationCreatedDto create() {
        String id = AgentConversationRepository.newWebConversationId();
        Instant now = Instant.now();
        conversationRepository.insert(id, "新对话", now);
        return new ConversationCreatedDto(id, "新对话", now.toString(), now.toString());
    }

    @GetMapping("/{conversationId}/messages")
    public List<MessageDto> messages(@PathVariable String conversationId) {
        requireConversation(conversationId);
        return postgresChatMemory.listTranscript(conversationId).stream()
                .map(row -> new MessageDto(row.id(), row.createdAt(), row.role(), row.text()))
                .toList();
    }

    @PatchMapping("/{conversationId}")
    public ConversationSummaryDto rename(
            @PathVariable String conversationId,
            @RequestBody RenameConversationDto body) {
        if (body == null || body.title() == null || body.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        requireConversation(conversationId);
        String title = body.title().strip();
        if (title.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title too long");
        }
        Instant now = Instant.now();
        conversationRepository.updateTitle(conversationId, title, now);
        return new ConversationSummaryDto(conversationId, title, null, now.toString());
    }

    @DeleteMapping("/{conversationId}")
    public void delete(@PathVariable String conversationId) {
        requireConversation(conversationId);
        conversationRepository.delete(conversationId);
    }

    private void requireConversation(String conversationId) {
        if (!conversationRepository.exists(conversationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }
    }

    public record ConversationSummaryDto(String id, String title, String createdAt, String updatedAt) {
    }

    public record ConversationCreatedDto(String id, String title, String createdAt, String updatedAt) {
    }

    public record MessageDto(long id, String createdAt, String role, String text) {
    }

    public record RenameConversationDto(String title) {
    }
}
