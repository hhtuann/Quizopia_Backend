package com.hhtuann.backend.realtime.config;

import com.hhtuann.backend.realtime.authorization.StompSendGuardInterceptor;
import com.hhtuann.backend.realtime.authorization.StompSubscribeInterceptor;
import com.hhtuann.backend.realtime.security.StompConnectInterceptor;
import com.hhtuann.backend.security.config.SecurityProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * STOMP-over-WebSocket configuration (Day 7 Realtime, frozen contract §9).
 *
 * <ul>
 *   <li>Handshake endpoint {@code /ws} (no SockJS — the frozen contract does not require it); allowed
 *       origins reuse the configured CORS origins (never a wildcard, since credentials are enabled).</li>
 *   <li>In-memory simple broker on {@code /topic} + {@code /queue}; user destination prefix
 *       {@code /user}; application prefix {@code /app} (unused in Day 7 — no client command).</li>
 *   <li>Inbound channel interceptors: CONNECT auth → SUBSCRIBE authorization → SEND/MESSAGE guard
 *       (order matters: authentication first, then authorization, then the spoof guard).</li>
 * </ul>
 *
 * <p>No Redis pubsub, no outbox, no {@code @Async}. The handshake is only an HTTP upgrade permit; it
 * is NOT authentication — every connection must still pass a valid Bearer access token in the STOMP
 * CONNECT frame (handled by {@link StompConnectInterceptor}).
 */
@Configuration
@EnableWebSocketMessageBroker
@SuppressWarnings({"null"})
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final SecurityProperties properties;
    private final StompConnectInterceptor connectInterceptor;
    private final StompSubscribeInterceptor subscribeInterceptor;
    private final StompSendGuardInterceptor sendGuardInterceptor;

    public WebSocketConfig(SecurityProperties properties, StompConnectInterceptor connectInterceptor,
                           StompSubscribeInterceptor subscribeInterceptor,
                           StompSendGuardInterceptor sendGuardInterceptor) {
        this.properties = properties;
        this.connectInterceptor = connectInterceptor;
        this.subscribeInterceptor = subscribeInterceptor;
        this.sendGuardInterceptor = sendGuardInterceptor;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Authentication → authorization → spoof guard.
        registration.interceptors(connectInterceptor, subscribeInterceptor, sendGuardInterceptor);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    /** Reuses the CORS origins (already validated against wildcard/empty by {@code CorsConfig}). */
    private String[] allowedOrigins() {
        List<String> origins = properties.getCors().getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            throw new IllegalStateException("quizopia.security.cors.allowed-origins must be configured for /ws");
        }
        return origins.stream().filter(o -> o != null && !o.isBlank()).map(String::trim).toArray(String[]::new);
    }
}
