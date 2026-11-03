package com.quizopia.backend.authentication.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Independent Nimbus-based validation of the Google ID token (ADR-002 §4).
 *
 * <p>Spring Security already validates the token inside the authorization-code
 * flow; this validator is the explicit, unit-tested second check performed by
 * the login handler before any user mapping: signature (JWKS, RS256/ES256),
 * issuer, audience (client id), expiry/issued-at, {@code azp} for multi-
 * audience tokens, nonce when supplied, and the {@code email_verified}
 * policy. All failures map to authentication errors and fail closed.
 *
 * <p>The {@link JWKSource} is constructor-injected: production uses the remote
 * Google JWKS (bean defined in {@link OAuth2ClientConfig}); tests override it
 * with an immutable local key set.
 */
@Component
public class GoogleIdTokenValidator {

    private static final Set<JWSAlgorithm> ACCEPTED_ALGORITHMS =
            Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);

    private final JWKSource<SecurityContext> jwkSource;
    private final OAuthProperties properties;
    private final Clock clock;

    public GoogleIdTokenValidator(JWKSource<SecurityContext> jwkSource,
            OAuthProperties properties, Clock clock) {
        this.jwkSource = jwkSource;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Validates the raw ID token and returns its identity claims.
     *
     * @param expectedNonce the nonce sent in the authorization request, or
     *                      {@code null} when the framework already enforced it
     */
    public ValidatedOidcIdentity validate(String idTokenString, String expectedNonce) {
        try {
            JWTClaimsSet claims = verifyAndExtractClaims(idTokenString);
            validateStandardClaims(claims, expectedNonce);
            return extractIdentity(claims);
        } catch (AuthenticationException ex) {
            throw ex;
        } catch (Exception ex) {
            // Claim parsing failures (ParseException) fail closed too.
            throw invalid(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
    }

    private JWTClaimsSet verifyAndExtractClaims(String idTokenString) {
        try {
            SignedJWT parsed = SignedJWT.parse(idTokenString);
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(
                    new JWSVerificationKeySelector<>(ACCEPTED_ALGORITHMS, jwkSource));
            // process() verifies the signature and returns the claims set;
            // any failure (bad signature, unknown key, malformed token) throws.
            return processor.process(parsed, null);
        } catch (AuthenticationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
    }




    private void validateStandardClaims(JWTClaimsSet claims, String expectedNonce)
            throws java.text.ParseException {
        String expectedIssuer = properties.getGoogle().getIssuer();
        if (expectedIssuer == null || !expectedIssuer.equals(claims.getIssuer())) {
            throw invalid(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
        List<String> audience = claims.getAudience();
        String clientId = properties.getGoogle().getClientId();
        if (audience == null || !audience.contains(clientId)) {
            throw invalid(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
        Date now = Date.from(java.time.Instant.now(clock));
        Date expiresAt = claims.getExpirationTime();
        Date issuedAt = claims.getIssueTime();
        if (expiresAt == null || !expiresAt.after(now)) {
            throw invalid(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
        if (issuedAt != null && issuedAt.after(Date.from(now.toInstant().plusSeconds(300)))) {
            // Issued unreasonably far in the future: clock skew guard.
            throw invalid(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
        if (audience.size() > 1) {
            String azp = claims.getStringClaim("azp");
            if (!clientId.equals(azp)) {
                throw invalid(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
            }
        }
        if (expectedNonce != null) {
            String nonce = claims.getStringClaim("nonce");
            if (!expectedNonce.equals(nonce)) {
                throw invalid(AuthErrorCode.AUTH_OAUTH_STATE_INVALID);
            }
        }
    }

    private ValidatedOidcIdentity extractIdentity(JWTClaimsSet claims)
            throws java.text.ParseException {
        String subject = claims.getSubject();
        String email = claims.getStringClaim("email");
        Boolean emailVerified = claims.getBooleanClaim("email_verified");
        if (subject == null || subject.isBlank()) {
            throw invalid(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
        if (email == null || email.isBlank()) {
            // No usable email: ownership of nothing can be established.
            throw invalid(AuthErrorCode.AUTH_OAUTH_EMAIL_NOT_VERIFIED);
        }
        if (!Boolean.TRUE.equals(emailVerified)) {
            throw invalid(AuthErrorCode.AUTH_OAUTH_EMAIL_NOT_VERIFIED);
        }
        return new ValidatedOidcIdentity(claims.getIssuer(), subject, email, true);
    }

    private static AuthenticationException invalid(AuthErrorCode code) {
        return new AuthenticationException(code);
    }
}
