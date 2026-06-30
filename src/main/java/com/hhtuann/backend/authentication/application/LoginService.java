package com.hhtuann.backend.authentication.application;

import com.hhtuann.backend.authentication.dto.ClientContext;
import com.hhtuann.backend.authentication.dto.LoginRequest;
import com.hhtuann.backend.authentication.dto.TokenResponse;
import com.hhtuann.backend.authentication.exception.AuthErrorCode;
import com.hhtuann.backend.authentication.exception.AuthenticationException;
import com.hhtuann.backend.identity.domain.model.RefreshSession;
import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.domain.model.UserStatus;
import com.hhtuann.backend.identity.repository.RefreshSessionRepository;
import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import com.hhtuann.backend.security.config.SecurityProperties;
import com.hhtuann.backend.security.password.PasswordHasher;
import com.hhtuann.backend.security.token.AccessTokenService;
import com.hhtuann.backend.security.token.IssuedAccessToken;
import com.hhtuann.backend.security.token.RefreshTokenGenerator;
import com.hhtuann.backend.security.token.RefreshTokenHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Username-or-email login with account lockout, Argon2id verification, lazy
 * Argon2 rehash on parameters change, access-token issuance and first refresh
 * session creation.
 *
 * <p>Timing equalization: when the identifier does not match a user, a real
 * Argon2 verify is still run against a fixed dummy hash (computed once at
 * construction) so a missing user and a wrong password take the same time, and
 * both return {@link AuthErrorCode#AUTH_INVALID_CREDENTIALS}.
 *
 * <p>Lockout is transient: it uses {@code failed_login_attempts} and
 * {@code locked_until} only, and never changes {@link UserStatus}. Five
 * consecutive failures lock for 15 minutes; a successful login or an expired
 * lock clears both fields.
 */
@Service
public class LoginService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshSessionRepository refreshSessionRepository;
    private final PasswordHasher passwordHasher;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenHasher refreshTokenHasher;
    private final SecurityProperties properties;
    private final Clock clock;

    /**
     * A fixed Argon2 hash used only to spend Argon2 verify time for a missing
     * user. It is computed once at construction and reused for every request, so
     * it does not add per-request hashing cost beyond the intended timing
     * equalization. It is never a credential and is never logged.
     */
    private final String dummyPasswordHash;

    public LoginService(UserRepository userRepository,
                        UserRoleRepository userRoleRepository,
                        RefreshSessionRepository refreshSessionRepository,
                        PasswordHasher passwordHasher,
                        AccessTokenService accessTokenService,
                        RefreshTokenGenerator refreshTokenGenerator,
                        RefreshTokenHasher refreshTokenHasher,
                        SecurityProperties properties,
                        Clock clock) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.refreshSessionRepository = refreshSessionRepository;
        this.passwordHasher = passwordHasher;
        this.accessTokenService = accessTokenService;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.refreshTokenHasher = refreshTokenHasher;
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = passwordHasher.hash("quizopia-login-timing-equalizer");
    }

    @Transactional
    public LoginResult login(LoginRequest request, ClientContext client) {
        String identifier = request.identifier();
        Optional<User> userOpt = identifier.contains("@")
                ? userRepository.findByEmailIgnoreCase(identifier)
                : userRepository.findByUsernameIgnoreCase(identifier);

        Instant now = Instant.now(clock);

        if (userOpt.isEmpty()) {
            // Spend Argon2 verify time so a missing user looks like a wrong password.
            passwordHasher.matches(request.password(), dummyPasswordHash);
            throw new AuthenticationException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        User user = userOpt.get();

        applyLockoutState(user, now);

        UserStatus status = user.getStatus();
        if (status == UserStatus.DISABLED) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (status == UserStatus.PENDING) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_PENDING);
        }

        if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
            recordFailedAttempt(user, now);
            userRepository.save(user);
            throw new AuthenticationException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        if (passwordHasher.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(passwordHasher.hash(request.password()));
        }
        userRepository.save(user);

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
                client.userAgent(),
                parseAddress(client.remoteAddress()),
                now.plus(familyLifetime));
        refreshSessionRepository.save(session);

        return new LoginResult(toTokenResponse(access, roleCodes), rawRefreshToken, familyLifetime);
    }

    /**
     * Enforces the lock window and clears an expired lock. Throws immediately if
     * the account is still within the lock window.
     */
    private void applyLockoutState(User user, Instant now) {
        Instant lockedUntil = user.getLockedUntil();
        if (lockedUntil == null) {
            return;
        }
        if (now.isBefore(lockedUntil)) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
        }
        // Lock window has elapsed: reset and continue evaluating this login.
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
    }

    private void recordFailedAttempt(User user, Instant now) {
        int attempts = (user.getFailedLoginAttempts() == null ? 0 : user.getFailedLoginAttempts()) + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= properties.getLockout().getThreshold()) {
            user.setLockedUntil(now.plus(properties.getLockout().getDuration()));
        }
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
     * Outcome of a successful login: the access-token response body and the raw
     * refresh token to place in the cookie, together with the cookie max age.
     */
    public record LoginResult(TokenResponse tokenResponse, String rawRefreshToken, Duration refreshMaxAge) {
    }
}
