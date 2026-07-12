package com.quizopia.backend.realtime.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link StompSendGuardInterceptor} (B1R4-A §7). Proves at the interceptor level
 * — without a Spring context or Docker — that every client {@code SEND}/{@code MESSAGE} frame is
 * rejected (including a crafted MESSAGE and a SEND with a null destination), while
 * {@code CONNECT}/{@code SUBSCRIBE}/{@code DISCONNECT} pass through untouched. The guard fires on
 * command before any destination check, so a null-destination SEND is still rejected.
 */
class StompSendGuardInterceptorUnitTests {

    private final StompSendGuardInterceptor interceptor = new StompSendGuardInterceptor();

    @Test
    void sendRejected() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/topic/exam-sessions/1"), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void craftedMessageRejected() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.MESSAGE, "/topic/exam-sessions/1"), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void sendWithNullDestinationRejected() {
        // No destination set — the guard rejects on command alone, before consulting destination.
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, null), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void connectAllowed() {
        assertThat(interceptor.preSend(frame(StompCommand.CONNECT, null), null)).isNotNull();
    }

    @Test
    void subscribeAllowed() {
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/attempt"), null)).isNotNull();
    }

    @Test
    void disconnectAllowed() {
        assertThat(interceptor.preSend(frame(StompCommand.DISCONNECT, null), null)).isNotNull();
    }

    private Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
