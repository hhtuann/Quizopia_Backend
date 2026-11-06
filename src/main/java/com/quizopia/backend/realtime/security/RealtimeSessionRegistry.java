package com.quizopia.backend.realtime.security;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Server-owned CONNECT bindings; native STOMP headers never supply identity. */
@Component
public class RealtimeSessionRegistry {
    private final ConcurrentMap<String, JwtAuthenticationToken> sessions = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RealtimeSessionRegistry(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void bind(String sessionId, JwtAuthenticationToken authentication) {
        if (sessionId == null || sessionId.isBlank() || !authentication.isAuthenticated()
                || sessions.putIfAbsent(sessionId, authentication) != null) {
            throw new IllegalStateException("CONNECT rejected");
        }
    }

    public boolean isCurrent(String sessionId) {
        JwtAuthenticationToken authentication = sessionId == null ? null : sessions.get(sessionId);
        if (authentication == null) {
            return false;
        }
        var jwt = authentication.getToken();
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !Instant.now(clock).isBefore(expiresAt)) {
            return false;
        }
        Object epoch = jwt.getClaim("token_version");
        if (!(epoch instanceof Number number)) {
            return false;
        }
        try {
            long userId = Long.parseLong(jwt.getSubject());
            // Scalar SQL intentionally avoids a potentially stale persistence-context User.
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND status = 'ACTIVE' AND token_version = ?)",
                    Boolean.class, userId, number.intValue()));
        } catch (RuntimeException exception) {
            // DB unavailability and malformed bindings both fail closed, without leaking credentials.
            return false;
        }
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        sessions.remove(event.getSessionId());
    }
}
