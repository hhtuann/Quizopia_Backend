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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Username-or-email login with account lockout, Argon2id verification and lazy
 * Argon2 rehash on parameter change. Token/session issuance is delegated to
 * {@link SessionIssuanceService}.
 *
 * <h2>Transaction boundaries (review finding M1-01, LoginService audit)</h2>
 * {@code login()} is intentionally NOT {@code @Transactional}: a failed-login
 * counter written inside a transaction that ends in an unchecked
 * authentication exception would roll back — the lockout would never persist
 * across real HTTP requests. Instead the failure counter is committed via an
 * atomic native UPDATE (own transaction, no lost updates under concurrency)
 * BEFORE the error is thrown; the success path runs in one short
 * {@link TransactionTemplate} transaction.
 *
 * <p>Timing equalization: when the identifier does not match a user, a real
 * Argon2 verify is still run against a fixed dummy hash (computed once at
 * construction) so a missing user and a wrong password take the same time,
 * and both return {@link AuthErrorCode#AUTH_INVALID_CREDENTIALS}.
 *
 * <p>Lockout is transient: it uses {@code failed_login_attempts} and
 * {@code locked_until} only, and never changes {@link UserStatus}. Five
 * consecutive failures lock for 15 minutes; a successful login or an expired
 * lock clears both fields.
 *
 * <p>Identity-M1 email gate: accounts whose login requires email verification
 * (everyone created after V18) must have proven ownership
 * ({@code email_verified_at}) before tokens are issued. Legacy accounts keep
 * logging in without proof (their gate flag is FALSE), but ownership-requiring
 * actions elsewhere never trust that flag (review M1-03). The check runs only
 * after the password verifies so it cannot enumerate accounts.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final SecurityProperties properties;
    private final Clock clock;
    private final SessionIssuanceService sessionIssuanceService;
    private final TransactionTemplate tx;

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
                        SessionIssuanceService sessionIssuanceService,
                        TransactionTemplate tx) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.properties = properties;
        this.clock = clock;
        this.sessionIssuanceService = sessionIssuanceService;
        this.tx = tx;
        this.dummyPasswordHash = passwordHasher.hash("quizopia-login-timing-equalizer");
    }

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

        // Lockout is evaluated on DATABASE-FRESH values: a previous failed
        // attempt may have committed locked_until via the atomic increment in
        // another transaction (or the same test), and the persistence-context
        // copy can be stale.
        applyLockoutState(user, freshLockedUntil(user), now);

        UserStatus status = user.getStatus();
        if (status == UserStatus.DISABLED) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (status == UserStatus.PENDING) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_PENDING);
        }

        if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
            // Committed BEFORE the throw: the lockout survives the error and
            // concurrent failures never lose increments (native atomic UPDATE).
            recordFailedAttempt(user, now);
            throw new AuthenticationException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        // Checked only AFTER the password verifies so an unauthenticated caller
        // cannot distinguish "wrong password" from "unverified email" and
        // enumerate accounts (ADR-002 §2).
        if (Boolean.TRUE.equals(user.getLoginRequiresEmailVerification())
                && user.getEmailVerifiedAt() == null) {
            throw new AuthenticationException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }

        return successfulLogin(user, request, now, client);
    }

    /** Clears transient lock state, applies lazy rehash, issues the session. */
    private SessionIssuanceService.IssuedSession successfulLogin(
            User user, LoginRequest request, Instant now, ClientContext client) {
        SessionIssuanceService.IssuedSession session = tx.execute(status -> {
            User managed = userRepository.findById(user.getId()).orElseThrow();
            managed.setFailedLoginAttempts(0);
            managed.setLockedUntil(null);
            managed.setLastLoginAt(now);
            if (passwordHasher.needsRehash(managed.getPasswordHash())) {
                managed.setPasswordHash(passwordHasher.hash(request.password()));
            }
            userRepository.saveAndFlush(managed);
            return sessionIssuanceService.issue(managed, client);
        });
        log.debug("Login succeeded for user {}", user.getId());
        return session;
    }

    /**
     * Enforces the lock window and clears an expired lock. Throws immediately if
     * the account is still within the lock window.
     */
    /** Database-fresh lock deadline (native read bypasses the L1 cache). */
    private Instant freshLockedUntil(User user) {
        java.sql.Timestamp ts = userRepository.findLockedUntilById(user.getId());
        return ts == null ? null : ts.toInstant();
    }

    private void applyLockoutState(User user, Instant lockedUntil, Instant now) {
        if (lockedUntil == null) {
            return;
        }
        if (now.isBefore(lockedUntil)) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
        }
        // Lock window has elapsed: reset and continue evaluating this login.
        tx.executeWithoutResult(status -> userRepository.findById(user.getId()).ifPresent(managed -> {
            managed.setLockedUntil(null);
            managed.setFailedLoginAttempts(0);
            userRepository.saveAndFlush(managed);
        }));
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
    }

    private void recordFailedAttempt(User user, Instant now) {
        Instant lockUntil = now.plus(properties.getLockout().getDuration());
        Integer attempts = tx.execute(status -> {
            userRepository.incrementFailedLoginAttempts(
                    user.getId(), properties.getLockout().getThreshold(), lockUntil);
            return userRepository.findFailedLoginAttempts(user.getId());
        });
        if (attempts != null && attempts >= properties.getLockout().getThreshold()) {
            log.info("Account {} locked after {} failed logins", user.getId(), attempts);
        }
    }
}
