package com.quizopia.backend.authentication.application;

import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.authentication.dto.TokenResponse;
import com.quizopia.backend.identity.domain.model.RefreshSession;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.RefreshSessionRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.security.config.SecurityProperties;
import com.quizopia.backend.security.token.AccessTokenService;
import com.quizopia.backend.security.token.IssuedAccessToken;
import com.quizopia.backend.security.token.RefreshTokenGenerator;
import com.quizopia.backend.security.token.RefreshTokenHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shared issuance of the Quizopia access token + first refresh session.
 * Extracted from LoginService so password login and the OAuth exchange
 * produce byte-for-byte the same session semantics (JWT claims, refresh
 * family lifetime, cookie max age).
 */
@Service
public class SessionIssuanceService {

    private final UserRoleRepository userRoleRepository;
    private final RefreshSessionRepository refreshSessionRepository;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenHasher refreshTokenHasher;
    private final SecurityProperties properties;
    private final Clock clock;

    public SessionIssuanceService(UserRoleRepository userRoleRepository,
            RefreshSessionRepository refreshSessionRepository,
            AccessTokenService accessTokenService,
            RefreshTokenGenerator refreshTokenGenerator,
            RefreshTokenHasher refreshTokenHasher,
            SecurityProperties properties,
            Clock clock) {
        this.userRoleRepository = userRoleRepository;
        this.refreshSessionRepository = refreshSessionRepository;
        this.accessTokenService = accessTokenService;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.refreshTokenHasher = refreshTokenHasher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Issues a fresh access token and the first refresh session of a new
     * family for the given (already authenticated) user.
     */
    @Transactional
    public IssuedSession issue(User user, ClientContext client) {
        Instant now = Instant.now(clock);
        List<String> roleCodes = userRoleRepository.findActiveRoleCodesByUserId(user.getId(), now);
        IssuedAccessToken access = accessTokenService.issue(
                user.getId(), user.getUsername(), roleCodes, user.getTokenVersion());

        String rawRefreshToken = refreshTokenGenerator.generate();
        String refreshHash = refreshTokenHasher.hash(rawRefreshToken);
        Duration familyLifetime = properties.getRefreshToken().getFamilyLifetime();
        RefreshSession session = new RefreshSession(
                UUID.randomUUID(),
                user,
                UUID.randomUUID(),
                refreshHash,
                client == null ? null : client.userAgent(),
                parseAddress(client == null ? null : client.remoteAddress()),
                now.plus(familyLifetime));
        refreshSessionRepository.save(session);

        return new IssuedSession(toTokenResponse(access, roleCodes), rawRefreshToken, familyLifetime);
    }

    private TokenResponse toTokenResponse(IssuedAccessToken access, List<String> roles) {
        long expiresInSeconds = properties.getJwt().getAccessTokenLifetime().toSeconds();
        return new TokenResponse(
                access.getTokenValue(),
                "Bearer",
                access.getExpiresAt(),
                expiresInSeconds,
                roles);
    }

    private static InetAddress parseAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(remoteAddress);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    /**
     * Outcome of session issuance: the access-token response body, the raw
     * refresh token to place in the cookie, and the cookie max age.
     */
    public record IssuedSession(TokenResponse tokenResponse, String rawRefreshToken, Duration refreshMaxAge) {
    }
}
