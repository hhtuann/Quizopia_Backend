package com.quizopia.backend.realtime.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

/** Rechecks committed account state at processing time, including queued recipient deliveries. */
@Component
public class StompSessionGuardInterceptor implements ExecutorChannelInterceptor {
    private final RealtimeSessionRegistry sessions;

    public StompSessionGuardInterceptor(RealtimeSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        // Reject stale subscriptions before SessionSubscribeEvent and its side effects are published.
        if (type == SimpMessageType.SUBSCRIBE && !isCurrent(message)) {
            throw new MessagingException("Session rejected");
        }
        return message;
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        // CONNECT/ACK, ERROR, heartbeat and disconnect/unsubscribe cleanup must remain possible.
        if (type == SimpMessageType.MESSAGE || type == SimpMessageType.SUBSCRIBE) {
            return isCurrent(message) ? message : null;
        }
        return message;
    }

    private boolean isCurrent(Message<?> message) {
        return sessions.isCurrent(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
    }
}
