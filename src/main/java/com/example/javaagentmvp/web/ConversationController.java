package com.example.javaagentmvp.web;

import com.example.javaagentmvp.auth.AuthRequestSupport;
import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.UserRole;
import com.example.javaagentmvp.chat.AgentConversationRepository;
import com.example.javaagentmvp.chat.ConversationAccessService;
import com.example.javaagentmvp.chat.PostgresChatMemory;
import com.example.javaagentmvp.chat.ui.ChatTable;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ConversationAccessService conversationAccess;

    public ConversationController(
            AgentConversationRepository conversationRepository,
            PostgresChatMemory postgresChatMemory,
            ConversationAccessService conversationAccess) {
        this.conversationRepository = conversationRepository;
        this.postgresChatMemory = postgresChatMemory;
        this.conversationAccess = conversationAccess;
    }

    @GetMapping
    public List<ConversationSummaryDto> list(HttpServletRequest request) {
        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        return conversationRepository.listSummaries(user.userId(), user.role() == UserRole.ADMIN).stream()
                .map(s -> new ConversationSummaryDto(s.id(), s.title(), s.createdAt(), s.updatedAt()))
                .toList();
    }

    @PostMapping
    public ConversationCreatedDto create(HttpServletRequest request) {
        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        String id = AgentConversationRepository.newWebConversationId();
        Instant now = Instant.now();
        conversationRepository.insert(id, "新对话", now, user.userId());
        return new ConversationCreatedDto(id, "新对话", now.toString(), now.toString());
    }

    @GetMapping("/{conversationId}/messages")
    public List<MessageDto> messages(@PathVariable String conversationId, HttpServletRequest request) {
        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        conversationAccess.requireAccess(conversationId, user);
        return postgresChatMemory.listTranscript(conversationId).stream()
                .map(row -> new MessageDto(row.id(), row.createdAt(), row.role(), row.text(), row.tables()))
                .toList();
    }

    @PatchMapping("/{conversationId}")
    public ConversationSummaryDto rename(
            @PathVariable String conversationId,
            @RequestBody RenameConversationDto body,
            HttpServletRequest request) {
        if (body == null || body.title() == null || body.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        conversationAccess.requireAccess(conversationId, user);
        String title = body.title().strip();
        if (title.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title too long");
        }
        Instant now = Instant.now();
        conversationRepository.updateTitle(conversationId, title, now, ownerScope(user));
        return new ConversationSummaryDto(conversationId, title, null, now.toString());
    }

    @DeleteMapping("/{conversationId}")
    public void delete(@PathVariable String conversationId, HttpServletRequest request) {
        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        conversationAccess.requireAccess(conversationId, user);
        conversationRepository.delete(conversationId, ownerScope(user));
    }

    @PostMapping("/{conversationId}/archive")
    public void archive(@PathVariable String conversationId, HttpServletRequest request) {
        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        conversationAccess.requireAccess(conversationId, user);
        conversationRepository.archive(conversationId, Instant.now(), ownerScope(user));
    }

    private static Long ownerScope(AuthenticatedUser user) {
        return user.role() == UserRole.ADMIN ? null : user.userId();
    }

    public record ConversationSummaryDto(String id, String title, String createdAt, String updatedAt) {
    }

    public record ConversationCreatedDto(String id, String title, String createdAt, String updatedAt) {
    }

    public record MessageDto(long id, String createdAt, String role, String text, List<ChatTable> tables) {
    }

    public record RenameConversationDto(String title) {
    }
}
