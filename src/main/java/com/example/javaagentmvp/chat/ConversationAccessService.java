package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationAccessService {

    private final AgentConversationRepository conversationRepository;

    public ConversationAccessService(AgentConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    public void requireAccess(String conversationId, AuthenticatedUser user) {
        if (!conversationRepository.existsForUser(conversationId, user.userId(), user.role() == UserRole.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }
    }
}
