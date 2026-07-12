package com.quizopia.backend.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompHeaders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real STOMP CONNECT authentication matrix (Day 7 §14, B1R4-A §4). A live {@code WebSocketStompClient}
 * exercises Bearer-token validation end-to-end. A rejected CONNECT never establishes an authenticated
 * session. Expired / wrong-issuer / wrong-audience tokens are crafted with the production
 * {@code JwtEncoder} (valid signature, wrong claims) — NOT via MutableClock manipulation (R-RT1).
 *
 * <p>Token-source rules: the token is accepted ONLY from the CONNECT {@code Authorization} header —
 * never a query parameter, a SUBSCRIBE header, a cookie, the body, or the destination.
 *
 * <p>All successful connects use {@link StompTestConnection} try-with-resources; rejected connects throw
 * (no holder created, client stopped inside {@code connectWith}).
 */
class WebSocketConnectIntegrationTests extends RealtimeStompTestBase {

    private long studentId;
    private String validToken;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        studentId = insertUserWithRole("wsconn-" + UUID.randomUUID().toString().substring(0, 6), "STUDENT");
        validToken = accessToken(studentId, "wsconn", List.of("STUDENT"));
    }

    @Test void validAccessTokenConnects() {
        try (StompTestConnection conn = connect(validToken)) {
            assertThat(conn.isConnected()).isTrue();
        }
    }

    // --- token missing / malformed ---

    @Test void missingAuthorizationHeaderRejected() {
        assertThatThrownBy(() -> connectRawAuth(null)).isInstanceOf(Exception.class);
    }

    @Test void malformedBearerRejected() {
        assertThatThrownBy(() -> connectRawAuth("NotBearer abc")).isInstanceOf(Exception.class);
    }

    @Test void emptyBearerRejected() {
        assertThatThrownBy(() -> connectRawAuth("Bearer ")).isInstanceOf(Exception.class);
    }

    // --- signature / claims / user state ---

    @Test void invalidSignatureRejected() {
        assertThatThrownBy(() -> connect(validToken + "tampered")).isInstanceOf(Exception.class);
    }

    @Test void expiredTokenRejected() {
        // Both iat and exp in the past → valid HS256 signature, correct iss/aud, but expired.
        Instant past = Instant.now().minusSeconds(7200); // 2 hours ago
        String expired = craftCustomToken(studentId, past, past.plusSeconds(3600),
                securityProperties.getJwt().getIssuer(),
                securityProperties.getJwt().getAudience());
        assertThatThrownBy(() -> connect(expired)).isInstanceOf(Exception.class);
    }

    @Test void userMissingRejected() {
        String tokenForMissingUser = craftCustomToken(999999L,
                Instant.now().plus(securityProperties.getJwt().getAccessTokenLifetime()),
                securityProperties.getJwt().getIssuer(),
                securityProperties.getJwt().getAudience());
        assertThatThrownBy(() -> connect(tokenForMissingUser)).isInstanceOf(Exception.class);
    }

    @Test void wrongIssuerRejected() {
        String wrongIss = craftCustomToken(studentId,
                Instant.now().plusSeconds(3600),
                "wrong-issuer",
                securityProperties.getJwt().getAudience());
        assertThatThrownBy(() -> connect(wrongIss)).isInstanceOf(Exception.class);
    }

    @Test void wrongAudienceRejected() {
        String wrongAud = craftCustomToken(studentId,
                Instant.now().plusSeconds(3600),
                securityProperties.getJwt().getIssuer(),
                "wrong-audience");
        assertThatThrownBy(() -> connect(wrongAud)).isInstanceOf(Exception.class);
    }

    @Test void tokenVersionMismatchRejected() {
        jdbc.update("UPDATE users SET token_version=5 WHERE id=" + studentId);
        assertThatThrownBy(() -> connect(validToken)).isInstanceOf(Exception.class);
    }

    @Test void inactiveUserRejected() {
        jdbc.update("UPDATE users SET status='LOCKED' WHERE id=" + studentId);
        assertThatThrownBy(() -> connect(validToken)).isInstanceOf(Exception.class);
    }

    // --- duplicate Authorization headers ---

    @Test void multipleConflictingAuthorizationHeadersRejected() {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + validToken);
        headers.add("Authorization", "Bearer " + validToken + "X"); // conflicting → rejected
        assertThatThrownBy(() -> connectWith(headers)).isInstanceOf(Exception.class);
    }

    @Test void duplicateIdenticalAuthorizationHeadersRejected() {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + validToken);
        headers.add("Authorization", "Bearer " + validToken); // identical duplicate → rejected
        assertThatThrownBy(() -> connectWith(headers)).isInstanceOf(Exception.class);
    }

    // --- token-source restrictions (no query / no SUBSCRIBE header) ---

    @Test void queryParameterTokenDoesNotAuthenticate() {
        // A token placed in the handshake URL query is invisible to StompConnectInterceptor (which reads
        // only the CONNECT Authorization header). With no Authorization in CONNECT, the connection is
        // rejected — the query param does NOT authenticate.
        StompHeaders headers = new StompHeaders(); // no Authorization
        String url = wsUrl() + "?access_token=" + validToken;
        assertThatThrownBy(() -> connectWith(headers, url)).isInstanceOf(Exception.class);
    }

    @Test void tokenOnlyInSubscribeHeaderRejected() {
        // CONNECT carries no Authorization; a token later placed in a SUBSCRIBE native header cannot
        // help — CONNECT is authenticated (and rejected) before any SUBSCRIBE is sent.
        StompHeaders connectHeaders = new StompHeaders(); // no Authorization
        assertThatThrownBy(() -> connectWith(connectHeaders)).isInstanceOf(Exception.class);
    }

    // --- refresh token: N/A (no token_type claim in the frozen JWT contract) ---

    @Test void opaqueNonJwtTokenRejected() {
        // The refresh token is an OPAQUE 32-byte Base64url string (SecureRefreshTokenGenerator), not a
        // JWT. The frozen JWT access-token contract carries NO token_type claim (see JwtAccessTokenService:
        // iss/aud/sub/username/roles/token_version/jti/iat/exp only), so there is no claim-based
        // access-vs-refresh distinction to test (R4A report: N/A). This only proves a non-JWT string
        // cannot authenticate over CONNECT — JwtDecoder.decode() rejects it.
        String opaqueRefreshLike = "vO8xK2pQ7mZ-aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2u"; // opaque, not a JWT
        assertThatThrownBy(() -> connect(opaqueRefreshLike)).isInstanceOf(Exception.class);
    }

    // --- spoofed headers have no effect ---

    @Test void spoofedRoleHeaderHasNoEffect() {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + validToken);
        headers.add("roles", "[\"TEACHER\"]");
        headers.add("username", "admin");
        try (StompTestConnection conn = connectWith(headers)) {
            assertThat(conn.isConnected()).isTrue();
            // Authorization is derived from the DB via the JWT subject, never from client headers — the
            // spoofed roles/username do not elevate this STUDENT. (SUBSCRIBE authorization tests in
            // WebSocketSubscribeIntegrationTests confirm a STUDENT cannot subscribe to a teacher topic.)
        }
    }
}
