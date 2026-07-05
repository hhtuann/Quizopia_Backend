package com.hhtuann.backend.realtime.authorization;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authorizes STOMP {@code SUBSCRIBE}. Only two destination shapes are permitted:
 * <ul>
 *   <li>{@code /user/queue/attempt} — any authenticated principal (Spring user-targeting guarantees
 *       each principal receives only its own queue; the username is not a path parameter the client
 *       can tamper with).</li>
 *   <li>{@code /topic/exam-sessions/{sessionId}} — owner teacher + same school +
 *       {@code EXAM_SESSION_MONITOR} (delegated to {@link RealtimeAuthorizationService}).</li>
 * </ul>
 * Every other destination, an unauthenticated frame, a malformed/tampered session id, a negative/zero
 * id, or an extra path segment is rejected with a generic {@link MessagingException} (ERROR frame —
 * no distinction between "session missing" and "not owner").
 */
@Component
public class StompSubscribeInterceptor implements ChannelInterceptor {

    static final String PERSONAL_QUEUE = "/user/queue/attempt";
    private static final Pattern TEACHER_TOPIC = Pattern.compile("^/topic/exam-sessions/(\\d+)$");

    private final RealtimeAuthorizationService authz;

    public StompSubscribeInterceptor(RealtimeAuthorizationService authz) {
        this.authz = authz;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            throw new MessagingException("denied");
        }
        Principal user = accessor.getUser();
        if (destination.equals(PERSONAL_QUEUE)) {
            if (user == null) {
                throw new MessagingException("denied"); // CONNECT must have authenticated first
            }
            return message;
        }
        Matcher matcher = TEACHER_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            throw new MessagingException("denied"); // unknown / malformed / extra-segment destination
        }
        if (user == null) {
            throw new MessagingException("denied");
        }
        Long sessionId;
        try {
            sessionId = Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new MessagingException("denied");
        }
        try {
            Long userId = Long.valueOf(user.getName());
            authz.authorizeTeacherTopic(userId, sessionId);
        } catch (RuntimeException e) {
            throw new MessagingException("denied"); // generic — no reason leaked
        }
        return message;
    }
}
