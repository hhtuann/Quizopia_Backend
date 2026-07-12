package com.quizopia.backend.realtime.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * B1R4-B2F8 §6.1 — unit tests for {@link ServerTimeSyncBrokerInterceptor}'s filtering. The interceptor must
 * emit ONLY after the simple broker has successfully handled a SUBSCRIBE to the personal queue (registration
 * guaranteed), with a valid principal + session id. Every other case is a no-op. The {@link ServerTimeSyncListener}
 * is mocked — these tests verify the interceptor delegates (or not) correctly; dedup is the listener's job.
 */
class ServerTimeSyncPostBrokerRegistrationTests {

    private ServerTimeSyncListener listener;
    private ServerTimeSyncBrokerInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);
    private final SimpleBrokerMessageHandler broker = mock(SimpleBrokerMessageHandler.class);
    private final Principal principal = () -> "123";

    @BeforeEach
    void setUp() {
        listener = mock(ServerTimeSyncListener.class);
        interceptor = new ServerTimeSyncBrokerInterceptor(listener);
    }

    @Test
    void wrongHandler_doesNotEmit() {
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/queue/attempt-usersess-A",
                "/user/queue/attempt", "sess-A", "sub-0", principal), channel, mock(MessageHandler.class), null);
        verifyNoInteractions(listener);
    }

    @Test
    void nonSubscribeCommand_doesNotEmit() {
        interceptor.afterMessageHandled(message(StompCommand.MESSAGE, "/queue/attempt-usersess-A",
                "/user/queue/attempt", "sess-A", "sub-0", principal), channel, broker, null);
        verifyNoInteractions(listener);
    }

    @Test
    void wrongOriginalDestination_doesNotEmit() {
        // a topic subscribe (orig != /user/queue/attempt) must not trigger a sync
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/topic/exam-sessions/50-sess-A",
                "/topic/exam-sessions/50", "sess-A", "sub-0", principal), channel, broker, null);
        verifyNoInteractions(listener);
    }

    @Test
    void handlerException_doesNotEmit() {
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/queue/attempt-usersess-A",
                "/user/queue/attempt", "sess-A", "sub-0", principal), channel, broker, new IllegalStateException("boom"));
        verifyNoInteractions(listener);
    }

    @Test
    void missingPrincipal_doesNotEmit() {
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/queue/attempt-usersess-A",
                "/user/queue/attempt", "sess-A", "sub-0", null), channel, broker, null);
        verifyNoInteractions(listener);
    }

    @Test
    void missingSessionId_doesNotEmit() {
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/queue/attempt-user?",
                "/user/queue/attempt", null, "sub-0", principal), channel, broker, null);
        verifyNoInteractions(listener);
    }

    @Test
    void successfulBrokerHandledPersonalSubscribe_emitsOnce() {
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/queue/attempt-usersess-A",
                "/user/queue/attempt", "sess-A", "sub-0", principal), channel, broker, null);
        verify(listener, times(1)).emitIfNeeded("sess-A", "sub-0");
    }

    @Test
    void differentSubscriptionInSameSession_emitsIndependently() {
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/queue/attempt-usersess-A",
                "/user/queue/attempt", "sess-A", "sub-0", principal), channel, broker, null);
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/queue/attempt-usersess-A",
                "/user/queue/attempt", "sess-A", "sub-1", principal), channel, broker, null);
        verify(listener, times(1)).emitIfNeeded("sess-A", "sub-0");
        verify(listener, times(1)).emitIfNeeded("sess-A", "sub-1");
    }

    @Test
    void differentSession_emitsIndependently() {
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/queue/attempt-usersess-A",
                "/user/queue/attempt", "sess-A", "sub-0", principal), channel, broker, null);
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/queue/attempt-usersess-B",
                "/user/queue/attempt", "sess-B", "sub-0", principal), channel, broker, null);
        verify(listener, times(1)).emitIfNeeded("sess-A", "sub-0");
        verify(listener, times(1)).emitIfNeeded("sess-B", "sub-0");
    }

    @Test
    void messageWithoutOriginalDestination_doesNotEmit() {
        // a SUBSCRIBE lacking the ORIGINAL_DESTINATION header (e.g. a raw broker-internal subscribe) is ignored
        interceptor.afterMessageHandled(message(StompCommand.SUBSCRIBE, "/queue/attempt-usersess-A",
                null, "sess-A", "sub-0", principal), channel, broker, null);
        verify(listener, never()).emitIfNeeded(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private Message<byte[]> message(StompCommand command, String destination, String originalDestination,
                                    String simpSessionId, String subscriptionId, Principal user) {
        StompHeaderAccessor a = StompHeaderAccessor.create(command);
        if (destination != null) {
            a.setDestination(destination);
        }
        if (originalDestination != null) {
            a.setNativeHeader(SimpMessageHeaderAccessor.ORIGINAL_DESTINATION, originalDestination);
        }
        if (simpSessionId != null) {
            a.setSessionId(simpSessionId);
        }
        if (subscriptionId != null) {
            a.setSubscriptionId(subscriptionId);
        }
        if (user != null) {
            a.setUser(user);
        }
        a.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }
}
