package com.quizopia.backend.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Registers the HS256 {@link JwtEncoder} and {@link JwtDecoder} beans built on
 * Nimbus (provided by Spring Security's resource-server support). No additional
 * JWT library is used.
 *
 * <p>Both beans share the same Base64 secret from {@link SecurityProperties.Jwt}.
 * The secret must decode to at least 32 bytes; otherwise bean creation fails
 * fast with a clear message. The key material is never logged.
 *
 * <p>The decoder validates signature, expiration, issuer and audience. Token
 * issuance is performed by {@code JwtAccessTokenService}; this class does not
 * issue tokens.
 */
@Configuration
public class JwtConfig {

    /**
     * Minimum key length in bytes for HS256.
     */
    static final int MIN_KEY_BYTES = 32;

    /**
     * HS256 JWT encoder backed by the shared secret.
     *
     * @param properties the security configuration
     * @return the encoder bean
     */
    @Bean
    JwtEncoder jwtEncoder(SecurityProperties properties) {
        SecretKey secretKey = hmacSha256Key(properties.getJwt().getSecretBase64());
        return NimbusJwtEncoder.withSecretKey(secretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * HS256 JWT decoder that validates signature, expiration, issuer and
     * audience.
     *
     * @param properties the security configuration
     * @return the decoder bean
     */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        SecretKey secretKey = hmacSha256Key(properties.getJwt().getSecretBase64());
        SecurityProperties.Jwt jwt = properties.getJwt();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(jwt.getIssuer()),
                audienceValidator(jwt.getAudience()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * Builds an audience validator that rejects tokens whose {@code aud} claim
     * is missing or does not contain the configured audience.
     *
     * @param expectedAudience the single allowed audience value
     * @return the validator
     */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            if (audience == null || !audience.contains(expectedAudience)) {
                OAuth2Error error = new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN,
                        "The aud claim is missing or does not contain the expected audience",
                        null);
                return OAuth2TokenValidatorResult.failure(error);
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    /**
     * Decodes the configured Base64 secret into an HMAC-SHA256 key, enforcing
     * the minimum length. The key bytes are never logged.
     *
     * @param secretBase64 the Base64-encoded secret from configuration
     * @return the HMAC key
     */
    private static SecretKey hmacSha256Key(String secretBase64) {
        byte[] keyBytes = decodeBase64(secretBase64, "QUIZOPIA_JWT_SECRET_BASE64");
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "QUIZOPIA_JWT_SECRET_BASE64 must decode to at least " + MIN_KEY_BYTES
                            + " bytes for HS256, but decoded to " + keyBytes.length + " bytes");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /**
     * Decodes a Base64 value with a clear error if it is missing or malformed.
     *
     * @param value       the Base64 string
     * @param envVarName  the originating environment variable, used in error messages
     * @return the decoded bytes
     */
    private static byte[] decodeBase64(String value, String envVarName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(envVarName + " is not configured");
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(envVarName + " is not valid Base64", ex);
        }
    }
}
