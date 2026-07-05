package com.hhtuann.backend.realtime.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import com.hhtuann.backend.security.authentication.QuizopiaJwtAuthenticationConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

/**
 * Authenticates a STOMP {@code CONNECT} frame with the exact same validation as the HTTP access-token
 * flow: a {@code Bearer <accessToken>} {@code Authorization} header is decoded by the shared
 * {@link JwtDecoder} (signature, expiration, issuer {@code quizopia-backend}, audience {@code quizopia-web})
 * and converted by {@link QuizopiaJwtAuthenticationConverter} (user exists, ACTIVE, tokenVersion matches).
 *
 * <p>On success the resulting principal is attached to the accessor so user-targeted delivery
 * ({@code convertAndSendToUser}) and the SUBSCRIBE authorization both work. On any failure a generic
 * {@link MessagingException} is thrown (the raw token and the JWT parser exception are never leaked);
 * Spring STOMP rejects the CONNECT (ERROR frame, no authenticated session).
 *
 * <p>The token is accepted <b>only</b> from the CONNECT {@code Authorization} header — never a query
 * parameter, refresh cookie, SUBSCRIBE header, destination, or body. This interceptor never calls
 * {@code SimpMessagingTemplate} and never sends {@code SERVER_TIME_SYNC}.
 */
@Component
public class StompConnectInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompConnectInterceptor.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final QuizopiaJwtAuthenticationConverter converter;

    public StompConnectInterceptor(JwtDecoder jwtDecoder, QuizopiaJwtAuthenticationConverter converter) {
        this.jwtDecoder = jwtDecoder;
        this.converter = converter;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message; // only CONNECT is authenticated here
        }
        // Reject multiple conflicting Authorization headers.
        java.util.List<String> allAuth = accessor.getNativeHeader(AUTHORIZATION_HEADER);
        if (allAuth != null && allAuth.size() > 1) {
            throw new MessagingException("CONNECT rejected");
        }
        // Token extraction via getFirstNativeHeader (stable across accessor types).
        String token = extractBearerToken(accessor.getFirstNativeHeader(AUTHORIZATION_HEADER));
        try {
            Jwt jwt = jwtDecoder.decode(token);
            AbstractAuthenticationToken authentication = converter.convert(jwt);
            accessor.setUser(authentication);
            return message;
        } catch (RuntimeException e) {
            log.warn("STOMP CONNECT rejected: {}", e.toString()); // server-side; no token in the message
            throw new MessagingException("CONNECT rejected");
        }
    }

    private static String extractBearerToken(String header) {
        if (header == null || header.isBlank()) {
            throw new MessagingException("CONNECT rejected");
        }
        String trimmed = header.trim();
        if (!trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new MessagingException("CONNECT rejected");
        }
        String token = trimmed.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new MessagingException("CONNECT rejected");
        }
        return token;
    }
}
