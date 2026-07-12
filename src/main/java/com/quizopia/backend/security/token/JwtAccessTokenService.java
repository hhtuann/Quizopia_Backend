package com.quizopia.backend.security.token;

import com.quizopia.backend.security.config.SecurityProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * JWT implementation of {@link AccessTokenService} using HS256 via the shared
 * {@link JwtEncoder} bean (Nimbus, provided by Spring Security's resource server
 * support).
 *
 * <p>Issue time and expiry are derived from the injected {@link Clock} so that
 * time-based behaviour is deterministic in tests. Only identity claims are
 * written; no email, phone, national identifier, password hash, refresh token
 * or permission data is ever placed in the token.
 */
@Component
public class JwtAccessTokenService implements AccessTokenService {

    /**
     * Claim name for the role codes list.
     */
    static final String ROLES_CLAIM = "roles";

    /**
     * Claim name for the token version used to invalidate access tokens.
     */
    static final String TOKEN_VERSION_CLAIM = "token_version";

    /**
     * Claim name for the username.
     */
    static final String USERNAME_CLAIM = "username";

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;
    private final Clock clock;

    /**
     * @param jwtEncoder  the shared JWT encoder bean; must not be {@code null}
     * @param properties  the security configuration; must not be {@code null}
     * @param clock       the time source used for {@code iat}/{@code exp}; must not be {@code null}
     */
    public JwtAccessTokenService(JwtEncoder jwtEncoder, SecurityProperties properties, Clock clock) {
        this.jwtEncoder = Objects.requireNonNull(jwtEncoder, "jwtEncoder must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public IssuedAccessToken issue(Long userId, String username, List<String> activeRoleCodes, int tokenVersion) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(activeRoleCodes, "activeRoleCodes must not be null");

        SecurityProperties.Jwt jwtConfig = properties.getJwt();
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(jwtConfig.getAccessTokenLifetime());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtConfig.getIssuer())
                .audience(List.of(jwtConfig.getAudience()))
                .subject(String.valueOf(userId))
                .claim(USERNAME_CLAIM, username)
                .claim(ROLES_CLAIM, List.copyOf(activeRoleCodes))
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));

        return new IssuedAccessToken(jwt.getTokenValue(), expiresAt);
    }
}
