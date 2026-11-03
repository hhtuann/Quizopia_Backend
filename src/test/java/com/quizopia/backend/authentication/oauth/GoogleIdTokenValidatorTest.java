package com.quizopia.backend.authentication.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GoogleIdTokenValidator} against locally-minted RSA
 * ID tokens (the "simulated provider"): every required failure mode — bad
 * signature, wrong issuer, wrong audience, expiry, nonce mismatch,
 * unverified email — must fail closed with an authentication error.
 */
class GoogleIdTokenValidatorTest {

    private static final String ISSUER = "https://fake-issuer.test";
    private static final String CLIENT_ID = "test-google-client-id";
    private static final String SUBJECT = "google-sub-" + UUID.randomUUID();
    private static final String EMAIL = "learner@example.com";
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private RSAKey signingKey;
    private RSAKey otherKey;
    private GoogleIdTokenValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048)
                .keyID("test-key-1").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        otherKey = new RSAKeyGenerator(2048)
                .keyID("test-key-2").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
        OAuthProperties properties = new OAuthProperties();
        properties.getGoogle().setClientId(CLIENT_ID);
        properties.getGoogle().setIssuer(ISSUER);
        validator = new GoogleIdTokenValidator(jwkSource, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void validTokenYieldsIdentity() {
        ValidatedOidcIdentity identity = validator.validate(token(defaultClaims()), null);
        assertThat(identity.issuer()).isEqualTo(ISSUER);
        assertThat(identity.subject()).isEqualTo(SUBJECT);
        assertThat(identity.email()).isEqualTo(EMAIL);
        assertThat(identity.emailVerified()).isTrue();
    }

    @Test
    void forgedSignatureIsRejected() throws Exception {
        String forged = mint(otherKey, defaultClaims());
        assertThatThrownBy(() -> validator.validate(forged, null))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(defaultClaims())
                .issuer("https://evil-issuer.test").build();
        assertThatThrownBy(() -> validator.validate(mint(signingKey, claims), null))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(defaultClaims())
                .audience("someone-elses-client-id").build();
        assertThatThrownBy(() -> validator.validate(mint(signingKey, claims), null))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(defaultClaims())
                .expirationTime(Date.from(NOW.minusSeconds(60))).build();
        assertThatThrownBy(() -> validator.validate(mint(signingKey, claims), null))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void nonceMismatchIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(defaultClaims())
                .claim("nonce", "the-real-nonce").build();
        assertThatThrownBy(() -> validator.validate(mint(signingKey, claims), "different-nonce"))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void unverifiedEmailIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(defaultClaims())
                .claim("email_verified", false).build();
        com.quizopia.backend.authentication.exception.AuthenticationException ex =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> validator.validate(mint(signingKey, claims), null),
                        com.quizopia.backend.authentication.exception.AuthenticationException.class);
        assertThat(ex.getErrorCode())
                .isEqualTo(com.quizopia.backend.authentication.exception.AuthErrorCode.AUTH_OAUTH_EMAIL_NOT_VERIFIED);
    }

    @Test
    void missingEmailIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(SUBJECT).audience(CLIENT_ID)
                .issueTime(Date.from(NOW.minusSeconds(30)))
                .expirationTime(Date.from(NOW.plusSeconds(300))).build();
        assertThatThrownBy(() -> validator.validate(mint(signingKey, claims), null))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void multiAudienceWithoutMatchingAzpIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(defaultClaims())
                .audience(List.of(CLIENT_ID, "second-audience"))
                .claim("azp", "second-audience").build();
        assertThatThrownBy(() -> validator.validate(mint(signingKey, claims), null))
                .isInstanceOf(AuthenticationException.class);
    }

    // ---------- helpers ----------

    private static JWTClaimsSet defaultClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(SUBJECT)
                .audience(CLIENT_ID)
                .claim("email", EMAIL)
                .claim("email_verified", true)
                .issueTime(Date.from(NOW.minusSeconds(30)))
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .build();
    }

    private String token(JWTClaimsSet claims) {
        try {
            return mint(signingKey, claims);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String mint(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
        return jwt.serialize();
    }
}
