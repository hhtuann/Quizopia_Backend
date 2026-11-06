package com.quizopia.backend.realtime;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.quizopia.backend.realtime.security.RealtimeSessionRegistry;
import com.quizopia.backend.realtime.security.StompConnectInterceptor;
import com.quizopia.backend.realtime.security.StompSessionGuardInterceptor;
import com.quizopia.backend.security.authentication.QuizopiaJwtAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class RealtimeSessionGuardTests {
    private final Instant now = Instant.parse("2026-11-06T12:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RealtimeSessionRegistry registry = new RealtimeSessionRegistry(jdbc, Clock.fixed(now, ZoneOffset.UTC));
    private final StompSessionGuardInterceptor guard = new StompSessionGuardInterceptor(registry);

    @Test
    void exactBindingRequiredEvenWithPrincipalAndCleanupRemainsAllowed() {
        var message = frame(SimpMessageType.MESSAGE, "unbound");
        assertThat(guard.beforeHandle(message, null, null)).isNull();
        registry.bind("bound", authentication(now.plusSeconds(60)));
        assertThat(guard.beforeHandle(message, null, null)).isNull();
        for (var type : List.of(SimpMessageType.DISCONNECT, SimpMessageType.UNSUBSCRIBE,
                SimpMessageType.OTHER, SimpMessageType.HEARTBEAT)) {
            var cleanup = frame(type, "unbound");
            assertThat(guard.beforeHandle(cleanup, null, null)).isSameAs(cleanup);
        }
    }

    @Test
    void expiryDatabaseRejectionAndDisconnectFailClosed() {
        registry.bind("expired", authentication(now));
        assertThat(registry.isCurrent("expired")).isFalse();
        registry.bind("active", authentication(now.plusSeconds(60)));
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class), eq(42L), eq(3))).thenReturn(true);
        assertThat(registry.isCurrent("active")).isTrue();
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class), eq(42L), eq(3))).thenReturn(false);
        assertThat(registry.isCurrent("active")).isFalse();
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class), eq(42L), eq(3))).thenReturn(true);
        registry.disconnected(new SessionDisconnectEvent(this, frame(SimpMessageType.DISCONNECT, "active"),
                "active", CloseStatus.NORMAL));
        assertThat(registry.isCurrent("active")).isFalse();
        assertThatThrownBy(() -> guard.preSend(frame(SimpMessageType.SUBSCRIBE, "active"), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void databaseExceptionDropsDeliveryWithoutLeakingCause() {
        registry.bind("active", authentication(now.plusSeconds(60)));
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class), eq(42L), eq(3)))
                .thenThrow(new IllegalStateException("database-secret"));
        assertThat(guard.beforeHandle(frame(SimpMessageType.MESSAGE, "active"), null, null)).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void bothConnectionCommandsAuthenticateAndBindExactSession(StompCommand command) {
        JwtDecoder decoder = mock(JwtDecoder.class);
        var converter = mock(QuizopiaJwtAuthenticationConverter.class);
        var authentication = authentication(now.plusSeconds(60));
        when(decoder.decode("secret")).thenReturn(authentication.getToken());
        when(converter.convert(authentication.getToken())).thenReturn(authentication);
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class), eq(42L), eq(3))).thenReturn(true);
        var connect = new StompConnectInterceptor(decoder, converter, registry);
        var headers = StompHeaderAccessor.create(command);
        headers.setSessionId("exact");
        headers.setNativeHeader("Authorization", "Bearer secret");
        headers.setLeaveMutable(true);
        connect.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null);
        assertThat(registry.isCurrent("exact")).isTrue();
        assertThat(registry.isCurrent("other")).isFalse();
        registry.disconnected(new SessionDisconnectEvent(this, frame(SimpMessageType.DISCONNECT, "exact"),
                "exact", CloseStatus.NORMAL));
        assertThat(registry.isCurrent("exact")).isFalse();
        // A still-valid JWT cannot keep the removed binding alive; the ID can be bound afresh.
        registry.bind("exact", authentication);
        assertThat(registry.isCurrent("exact")).isTrue();
    }

    @Test
    void connectFailureLogsOnlyClass(CapturedOutput output) {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("raw-secret-sentinel")).thenThrow(new IllegalArgumentException("nested-secret-sentinel"));
        var connect = new StompConnectInterceptor(decoder, mock(QuizopiaJwtAuthenticationConverter.class), registry);
        var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setSessionId("connect");
        headers.setNativeHeader("Authorization", "Bearer raw-secret-sentinel");
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        assertThatThrownBy(() -> connect.preSend(message, null)).isInstanceOf(MessagingException.class)
                .hasMessage("CONNECT rejected");
        assertThat(output.getAll()).contains("IllegalArgumentException")
                .doesNotContain("raw-secret-sentinel", "nested-secret-sentinel");
    }

    private JwtAuthenticationToken authentication(Instant expiresAt) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("secret").header("alg", "HS256")
                .subject("42").claim("token_version", 3).issuedAt(now.minusSeconds(60)).expiresAt(expiresAt).build(), List.of());
    }

    private Message<byte[]> frame(SimpMessageType type, String session) {
        var headers = SimpMessageHeaderAccessor.create(type);
        headers.setSessionId(session);
        headers.setUser(authentication(now.plusSeconds(60)));
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
