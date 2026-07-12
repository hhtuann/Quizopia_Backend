package com.quizopia.backend.authentication.application;

import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.authentication.dto.TokenResponse;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.RefreshSession;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Refresh-token rotation with reuse detection.
 *
 * <p>The raw refresh token is read from the {@code quizopia_refresh} cookie by
 * the controller; only its hash is looked up. The lookup acquires a pessimistic
 * write lock (with the user fetched in the same query) so two concurrent
 * refreshes of the same token are serialized: the winner rotates, the loser
 * re-reads the now-replaced session and trips reuse detection.
 *
 * <p>Validation order: not-found -> revoked-with-replacement (reuse, revoke the
 * whole family) -> revoked-without-replacement -> expired -> non-ACTIVE user.
 * Rotation keeps the same family id and the same expiry (the family lifetime is
 * never extended); the old session points at its replacement and is marked
 * {@code ROTATED}.
 */
@Service
public class RefreshService {

    static final String REASON_ROTATED = "ROTATED";
    static final String REASON_REUSE = "TOKEN_REUSE_DETECTED";

    private final RefreshSessionRepository refreshSessionRepository;
    private final UserRoleRepository userRoleRepository;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenHasher refreshTokenHasher;
    private final SecurityProperties properties;
    private final Clock clock;

    public RefreshService(RefreshSessionRepository refreshSessionRepository,
                          UserRoleRepository userRoleRepository,
                          AccessTokenService accessTokenService,
                          RefreshTokenGenerator refreshTokenGenerator,
                          RefreshTokenHasher refreshTokenHasher,
                          SecurityProperties properties,
                          Clock clock) {
        this.refreshSessionRepository = refreshSessionRepository;
        this.userRoleRepository = userRoleRepository;
        this.accessTokenService = accessTokenService;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.refreshTokenHasher = refreshTokenHasher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * noRollbackFor = AuthenticationException: the refresh flow deliberately
     * throws an {@link AuthenticationException} after performing a write only in
     * the reuse case (family-wide revocation). That revocation must persist even
     * though the request is rejected, so the transaction must not roll back on
     * this exception. Other failure branches perform no write, so non-rollback
     * is harmless for them.
     */
    @Transactional(noRollbackFor = AuthenticationException.class)
    public RefreshResult refresh(String rawRefreshToken, ClientContext client) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new AuthenticationException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);

        RefreshSession session = refreshSessionRepository
                .findForUpdateByTokenHashWithUser(tokenHash)
                .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        // Captured AFTER the pessimistic-lock lookup: the lookup flushes any
        // pending session insert, so created_at is finalized by the time we read
        // the clock. This guarantees revoked_at >= created_at for the rotated and
        // reuse-revoked rows (chk_refresh_sessions_revocation_time), and that a
        // concurrent winner's just-created sibling has created_at <= now.
        Instant now = Instant.now(clock);

        if (session.getRevokedAt() != null) {
            if (session.getReplacedBySession() != null) {
                // A rotated token is being presented again: revoke the whole family.
                // This revocation persists because @Transactional is configured with
                // noRollbackFor = AuthenticationException, so throwing the reuse error
                // does not roll back the family-wide revocation.
                refreshSessionRepository.revokeUnrevokedByFamilyId(session.getFamilyId(), now, REASON_REUSE);
                throw new AuthenticationException(AuthErrorCode.AUTH_REFRESH_TOKEN_REUSE_DETECTED);
            }
            throw new AuthenticationException(AuthErrorCode.AUTH_REFRESH_TOKEN_REVOKED);
        }
        if (!session.getExpiresAt().isAfter(now)) {
            throw new AuthenticationException(AuthErrorCode.AUTH_REFRESH_TOKEN_EXPIRED);
        }

        User user = session.getUser();
        UserStatus status = user.getStatus();
        if (status == UserStatus.DISABLED) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (status == UserStatus.PENDING) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_PENDING);
        }

        String newRawToken = rotate(now, session, user, client);

        List<String> roleCodes = userRoleRepository.findActiveRoleCodesByUserId(user.getId(), now);
        IssuedAccessToken access = accessTokenService.issue(
                user.getId(), user.getUsername(), roleCodes, user.getTokenVersion());

        // Cookie outlives the family by exactly the remaining time, so it and
        // the session expire together (the family lifetime is never extended).
        java.time.Duration refreshMaxAge = java.time.Duration.between(now, session.getExpiresAt());
        return new RefreshResult(toTokenResponse(access, roleCodes), newRawToken, refreshMaxAge);
    }

    /**
     * Creates the replacement session (keeping family id and expiry, linking the
     * old session as parent) and marks the old session as rotated. The child is
     * persisted before the parent's {@code replaced_by_session_id} is set so the
     * foreign key resolves, and every V2 CHECK constraint holds by construction.
     *
     * @return the raw replacement token (to be placed in the cookie)
     */
    private String rotate(Instant now, RefreshSession old, User user, ClientContext client) {
        String newRawToken = refreshTokenGenerator.generate();
        String newHash = refreshTokenHasher.hash(newRawToken);

        RefreshSession replacement = new RefreshSession(
                UUID.randomUUID(),
                user,
                old.getFamilyId(),
                newHash,
                client.userAgent(),
                parseAddress(client.remoteAddress()),
                old.getExpiresAt());
        replacement.setParentSession(old);
        refreshSessionRepository.saveAndFlush(replacement);

        old.setReplacedBySession(replacement);
        old.setRevokedAt(now);
        old.setRevokeReason(REASON_ROTATED);
        refreshSessionRepository.saveAndFlush(old);

        return newRawToken;
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
     * Outcome of a successful rotation: the new access-token response body, the
     * raw replacement token (for the cookie) and the cookie max age (the
     * remaining family lifetime).
     */
    public record RefreshResult(TokenResponse tokenResponse, String rawRefreshToken, java.time.Duration refreshMaxAge) {
    }
}
