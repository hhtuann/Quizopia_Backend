package com.quizopia.backend.authentication.application;

import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.authentication.dto.LoginRequest;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.security.config.SecurityProperties;
import com.quizopia.backend.security.password.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Username-or-email login with account lockout, Argon2id verification and lazy
 * Argon2 rehash on parameter change. Token/session issuance is delegated to
 * {@link SessionIssuanceService}.
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
 *
 * <p>Identity-M1: login additionally requires {@code email_verified_at} to be
 * set. The check runs only after the password verifies so the error cannot be
 * used to enumerate accounts (ADR-002 §2).
 */
@Service
public class LoginService {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final SecurityProperties properties;
    private final Clock clock;
    private final SessionIssuanceService sessionIssuanceService;

    /**
     * A fixed Argon2 hash used only to spend Argon2 verify time for a missing
     * user. It is computed once at construction and reused for every request, so
     * it does not add per-request hashing cost beyond the intended timing
     * equalization. It is never a credential and is never logged.
     */
    private final String dummyPasswordHash;

    public LoginService(UserRepository userRepository,
                        PasswordHasher passwordHasher,
                        SecurityProperties properties,
                        Clock clock,
                        SessionIssuanceService sessionIssuanceService) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.properties = properties;
        this.clock = clock;
        this.sessionIssuanceService = sessionIssuanceService;
        this.dummyPasswordHash = passwordHasher.hash("quizopia-login-timing-equalizer");
    }

    @Transactional
    public SessionIssuanceService.IssuedSession login(LoginRequest request, ClientContext client) {
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

        // Identity-M1: checked only AFTER the password verifies so an
        // unauthenticated caller cannot distinguish "wrong password" from
        // "unverified email" and enumerate accounts (ADR-002 §2).
        if (user.getEmailVerifiedAt() == null) {
            throw new AuthenticationException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        if (passwordHasher.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(passwordHasher.hash(request.password()));
        }
        userRepository.save(user);

        return sessionIssuanceService.issue(user, client);
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
}
