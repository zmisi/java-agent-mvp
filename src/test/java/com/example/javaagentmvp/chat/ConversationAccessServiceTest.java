package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationAccessServiceTest {

    @Mock
    private AgentConversationRepository conversationRepository;

    @InjectMocks
    private ConversationAccessService conversationAccess;

    @Test
    void requireAccess_allowsOwner() {
        AuthenticatedUser user = new AuthenticatedUser(42L, "openid-42", UserRole.MEMBER, "session", "jti");
        when(conversationRepository.existsForUser("conv-1", 42L)).thenReturn(true);

        assertThatCode(() -> conversationAccess.requireAccess("conv-1", user))
                .doesNotThrowAnyException();
    }

    @Test
    void requireAccess_deniesOtherUsers_evenForAdmin() {
        AuthenticatedUser admin = new AuthenticatedUser(1L, "openid-1", UserRole.ADMIN, "session", "jti");
        when(conversationRepository.existsForUser("conv-1", 1L)).thenReturn(false);

        assertThatThrownBy(() -> conversationAccess.requireAccess("conv-1", admin))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("conversation not found");
    }
}
