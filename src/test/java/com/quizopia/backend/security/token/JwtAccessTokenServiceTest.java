package com.quizopia.backend.security.token;

import com.quizopia.backend.security.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtAccessTokenService} together with the HS256 decoder
 * configuration that {@code JwtConfig} produces.
 *
 * <p>The encoder and decoder are built directly from a test secret so the tests
 * run without the Spring context and can control the clock, issuer, audience
 * and key for each scenario. The real bean wiring is covered by the application
 * context load test.
 */
class JwtAccessTokenServiceTest {

    private static final String ISSUER = "quizopia-backend";
    private static final String AUDIENCE = "quizopia-web";
    private static final String TEST_KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);

    private SecurityProperties properties;
    private JwtEncoder encoder;
    private JwtAccessTokenService tokenService;

    @BeforeEach
    void setUp() {
        properties = new SecurityProperties();
        properties.getJwt().setSecretBase64(TEST_KEY_BASE64);
        properties.getJwt().setIssuer(ISSUER);
        properties.getJwt().setAudience(AUDIENCE);
        properties.getJwt().setAccessTokenLifetime(Duration.ofMinutes(15));

        SecretKey key = hmacKey(TEST_KEY_BASE64);
        encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        tokenService = new JwtAccessTokenService(encoder, properties, Clock.systemUTC());
    }

    @Test
    void issueThenDecodeSucceedsWithCorrectClaims() {
        JwtDecoder decoder = decoder(TEST_KEY_BASE64, ISSUER, AUDIENCE);

        IssuedAccessToken issued = tokenService.issue(42L, "tuannh", List.of("TEACHER"), 3);
        Jwt jwt = decoder.decode(issued.getTokenValue());

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat((String) jwt.getClaim("username")).isEqualTo("tuannh");
        assertThat(jwt.getId()).isNotNull();
        assertThat((String) jwt.getClaim("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).contains(AUDIENCE);
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isNotNull();
    }

    @Test
    void tokenExpiresExactlyFifteenMinutesAfterIssue() {
        Instant before = Instant.now();

        IssuedAccessToken issued = tokenService.issue(1L, "alice", List.of("STUDENT"), 0);

        Instant after = Instant.now();
        Duration lifetime = Duration.between(issued.getExpiresAt(), before).abs();

        // Expiry is issuedAt + 15m; allow only the execution window slack.
        assertThat(issued.getExpiresAt()).isAfterOrEqualTo(before.plus(Duration.ofMinutes(15)));
        assertThat(issued.getExpiresAt()).isBeforeOrEqualTo(after.plus(Duration.ofMinutes(15)));
        assertThat(lifetime).isCloseTo(Duration.ofMinutes(15), Duration.ofSeconds(5));
    }

    @Test
    void tokenContainsRolesAndTokenVersionClaims() {
        JwtDecoder decoder = decoder(TEST_KEY_BASE64, ISSUER, AUDIENCE);

        IssuedAccessToken issued = tokenService.issue(7L, "bob", List.of("TEACHER", "STUDENT"), 9);
        Jwt jwt = decoder.decode(issued.getTokenValue());

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) jwt.getClaim("roles");
        assertThat(roles).containsExactly("TEACHER", "STUDENT");
        // Nimbus parses JSON numbers as Long; use Number to stay type-agnostic.
        assertThat(((Number) jwt.getClaim("token_version")).intValue()).isEqualTo(9);
    }

    @Test
    void tokenDoesNotContainSensitiveOrPermissionClaims() {
        JwtDecoder decoder = decoder(TEST_KEY_BASE64, ISSUER, AUDIENCE);

        IssuedAccessToken issued = tokenService.issue(7L, "bob", List.of("TEACHER"), 9);
        Jwt jwt = decoder.decode(issued.getTokenValue());

        assertThat(jwt.getClaims().keySet())
                .doesNotContain("email", "phone", "password_hash", "permissions");
    }

    @Test
    void eachIssuedTokenHasAFreshJti() {
        JwtDecoder decoder = decoder(TEST_KEY_BASE64, ISSUER, AUDIENCE);

        Jwt first = decoder.decode(tokenService.issue(1L, "alice", List.of("STUDENT"), 0).getTokenValue());
        Jwt second = decoder.decode(tokenService.issue(1L, "alice", List.of("STUDENT"), 0).getTokenValue());

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        // The token was signed with the all-zero test key.
        IssuedAccessToken issued = tokenService.issue(1L, "alice", List.of("STUDENT"), 0);

        // Decode with a key derived from a different secret.
        String otherKeyBase64 = Base64.getEncoder().encodeToString(new byte[32]).replace('A', 'B');
        JwtDecoder decoderWithOtherKey = decoder(otherKeyBase64, ISSUER, AUDIENCE);

        assertThatThrownBy(() -> decoderWithOtherKey.decode(issued.getTokenValue()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenWithWrongIssuerIsRejected() {
        IssuedAccessToken issued = tokenService.issue(1L, "alice", List.of("STUDENT"), 0);

        JwtDecoder decoderExpectingOtherIssuer = decoder(TEST_KEY_BASE64, "some-other-issuer", AUDIENCE);

        assertThatThrownBy(() -> decoderExpectingOtherIssuer.decode(issued.getTokenValue()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenWithWrongAudienceIsRejected() {
        IssuedAccessToken issued = tokenService.issue(1L, "alice", List.of("STUDENT"), 0);

        JwtDecoder decoderExpectingOtherAudience = decoder(TEST_KEY_BASE64, ISSUER, "some-other-audience");

        assertThatThrownBy(() -> decoderExpectingOtherAudience.decode(issued.getTokenValue()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        // Issue with a clock fixed one hour in the past so exp (now-45m) is
        // already long expired relative to the decoder's real clock.
        Clock pastClock = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        JwtAccessTokenService pastService = new JwtAccessTokenService(encoder, properties, pastClock);

        IssuedAccessToken issued = pastService.issue(1L, "alice", List.of("STUDENT"), 0);

        JwtDecoder decoder = decoder(TEST_KEY_BASE64, ISSUER, AUDIENCE);
        assertThatThrownBy(() -> decoder.decode(issued.getTokenValue()))
                .isInstanceOf(JwtException.class);
    }

    // ------------------------------------------------------------------
    // Helpers mirroring JwtConfig's decoder/validator configuration.
    // ------------------------------------------------------------------

    private static JwtDecoder decoder(String keyBase64, String issuer, String audience) {
        SecretKey key = hmacKey(keyBase64);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                audienceValidator(audience));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return jwt -> {
            List<String> aud = jwt.getAudience();
            if (aud == null || !aud.contains(expectedAudience)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN,
                        "The aud claim is missing or does not contain the expected audience",
                        null));
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static SecretKey hmacKey(String keyBase64) {
        byte[] bytes = Base64.getDecoder().decode(keyBase64);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
