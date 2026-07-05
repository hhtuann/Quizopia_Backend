package com.hhtuann.backend.realtime.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Pure unit tests for {@link StompSubscribeInterceptor} (B1R4-A1 §7). Real-STOMP CONNECT always
 * authenticates before SUBSCRIBE is reachable, so the {@code user == null} branches of the interceptor
 * cannot be exercised end-to-end — these cover them at the interceptor level: a SUBSCRIBE with no
 * authenticated principal (to the personal queue or a teacher topic) is denied, and the destination
 * parser accepts only the two canonical shapes. The {@link RealtimeAuthorizationService} is mocked and
 * never reached by these cases.
 */
class StompSubscribeInterceptorUnitTests {

    private final RealtimeAuthorizationService authz = mock(RealtimeAuthorizationService.class);
    private final StompSubscribeInterceptor interceptor = new StompSubscribeInterceptor(authz);

    @Test
    void personalQueueWithoutPrincipalDenied() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/attempt", null), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void teacherTopicWithoutPrincipalDenied() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/exam-sessions/50", null), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void nullDestinationDenied() {
        Principal user = () -> "1";
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, null, user), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void malformedDestinationDenied() {
        Principal user = () -> "1";
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/exam-sessions/abc", user), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void personalQueueWithPrincipalAccepted() {
        Principal user = () -> "1";
        // The interceptor returns the message unchanged for an authorized personal-queue SUBSCRIBE.
        Message<?> msg = interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/attempt", user), null);
        assertThat(msg).isNotNull();
    }

    @Test
    void nonSubscribeCommandsPassThrough() {
        Principal user = () -> "1";
        // CONNECT / DISCONNECT are not handled here (they pass through unchanged).
        assertThat(interceptor.preSend(frame(StompCommand.CONNECT, null, user), null)).isNotNull();
        assertThat(interceptor.preSend(frame(StompCommand.DISCONNECT, null, user), null)).isNotNull();
    }

    private Message<byte[]> frame(StompCommand command, String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
