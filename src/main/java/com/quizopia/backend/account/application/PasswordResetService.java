package com.quizopia.backend.account.application;

import com.quizopia.backend.authentication.application.CredentialSecurityService;
import com.quizopia.backend.identity.domain.model.IdentityAuditEvent;
import com.quizopia.backend.identity.domain.model.LocalCredentialState;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.security.config.SecurityProperties;
import com.quizopia.backend.security.password.PasswordHasher;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-reset challenges per ADR-004 §"Reset persistence, intake and
 * delivery".
 *
 * <ul>
 *   <li>32 SecureRandom bytes, Base64URL without padding; only a
 *       domain-separated HMAC-SHA-256 digest (64 lowercase hex) is stored.</li>
 *   <li>One PENDING challenge per user; replacement terminalizes and flushes
 *       the predecessor under the user lock before inserting the successor.</li>
 *   <li>Challenges are bound to the normalized email, database-precision
 *       {@code emailVerifiedAt} and credential epoch at issuance, so email
 *       reassignment or any credential mutation invalidates them.</li>
 *   <li>Consumption is user-first/challenge-second under one lock: eligibility,
 *       all bindings, PENDING state and expiry are rechecked, then the token is
 *       consumed and the credential mutated atomically. Expiry is a predicate
 *       on {@code expires_at}, never a fourth status.</li>
 *   <li>Every application-supplied timestamp is truncated to microseconds
 *       (PostgreSQL TIMESTAMPTZ precision) so binding comparisons never see a
 *       precision drift between Java and the database.</li>
 * </ul>
 *
 * <p>The raw token is returned exactly once to the caller (the intake worker)
 * and appears only in the reset URL handed to the mail sender — never in the
 * database, audit, metrics or logs.
 */
@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Base64.Encoder URL_NO_PAD = Base64.getUrlEncoder().withoutPadding();

    private final UserRepository users;
    private final CredentialSecurityService credentials;
    private final PasswordHasher passwords;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final PasswordResetProperties properties;
    private final byte[] hmacKey;
    private final String resetBaseUri;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(UserRepository users,
                                CredentialSecurityService credentials,
                                PasswordHasher passwords,
                                JdbcTemplate jdbc,
                                TransactionTemplate transactions,
                                Clock clock,
                                PasswordResetProperties properties,
                                SecurityProperties security) {
        this.users = users;
        this.credentials = credentials;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
        this.hmacKey = hmac(decodeKey(security.getEncryption().getKeyBase64()),
                "quizopia.reset.hmac.v1".getBytes(StandardCharsets.UTF_8));
        this.resetBaseUri = validatedBaseUri(properties.getFrontendBaseUri(), security.isProduction());
    }

    /** A freshly issued challenge: the raw token plus the captured delivery values. */
    public record IssuedReset(String rawToken, String email, String displayName) {
    }

    /**
     * Eligibility issue kept as the public intake API: returns the raw token
     * exactly once, or {@code null} when the address is unknown or ineligible.
     * Callers needing the captured delivery values use {@link #issue(String)}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issueForEmail(String normalizedEmail) {
        IssuedReset issued = issue(normalizedEmail);
        return issued == null ? null : issued.rawToken();
    }

    /**
     * Issues a challenge for the address under the user lock, quietly skipping
     * every ineligible state (unknown address, unverified email, non-ENABLED
     * local credential, non-ACTIVE account, active temporary lock): the intake
     * response is generic, so issuance failures are a non-event, not an error.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedReset issue(String normalizedEmail) {
        String address = normalizedEmail == null ? "" : normalizedEmail.trim();
        // Non-locking ID projection first; every decision below uses the
        // freshly locked row (ADR-004 §"Transaction and revocation invariant").
        Long userId = users.findIdByEmailIgnoreCase(address).orElse(null);
        if (userId == null) {
            return null;
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        User user = credentials.lockUser(userId);
        String boundEmail = user.getEmail().trim();
        if (!boundEmail.equalsIgnoreCase(address)
                || user.getEmailVerifiedAt() == null
                || user.getLocalCredentialState() != LocalCredentialState.ENABLED
                || user.getStatus() != UserStatus.ACTIVE
                || (user.getLockedUntil() != null && now.isBefore(user.getLockedUntil()))) {

            return null;
        }
        String raw = generateToken();
        // Terminalize and flush the PENDING predecessor BEFORE inserting the
        // replacement so the partial unique index (user_id WHERE PENDING)
        // observes the terminal state inside this transaction. Both statements
        // are JDBC updates, so the flush ordering is the execution ordering.
        jdbc.update("UPDATE password_reset_challenges SET status = 'SUPERSEDED', superseded_at = ?"
                + " WHERE user_id = ? AND status = 'PENDING'", Timestamp.from(now), userId);
        jdbc.update("INSERT INTO password_reset_challenges"
                + " (id, user_id, token_hash, email_binding, email_verified_at, credential_epoch,"
                + "  status, created_at, expires_at)"
                + " VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'PENDING', ?, ?)",
                userId, hashToken(raw), boundEmail, Timestamp.from(user.getEmailVerifiedAt()),
                user.getTokenVersion(), Timestamp.from(now), Timestamp.from(now.plus(properties.getTtl())));
        log.info("Password reset challenge issued for user {}", userId);
        return new IssuedReset(raw, boundEmail, user.getDisplayName());
    }

    /**
     * Atomic eligibility recheck + consumption + credential mutation.
     * Returns {@code false} for every rejected token (unknown, invalid,
     * expired, replaced, replayed, binding drift, current-password reuse)
     * so the endpoint can answer with the single shared 400 code. Account
     * state problems (PENDING/DISABLED/locked) still throw their existing
     * 403/423 errors after the transaction rolled back, leaving the
     * challenge PENDING so the user can retry after the lock window.
     */
    public boolean reset(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        String hash = hashToken(rawToken.trim());
        Boolean consumed = transactions.execute(status -> {
            // Non-locking ID projection only; no authorization uses this snapshot.
            List<Map<String, Object>> owners = jdbc.queryForList(
                    "SELECT user_id FROM password_reset_challenges WHERE token_hash = ?", hash);
            if (owners.isEmpty()) {
                return Boolean.FALSE;
            }
            Long userId = ((Number) owners.get(0).get("user_id")).longValue();
            // Consume user-first/challenge-second (ADR-004 §Reset).
            User user;
            try {
                user = credentials.lockUser(userId);
            } catch (AuthenticationException vanished) {
                // The owner row disappeared between the projection and the lock
                // (user cascade): indistinguishable from any other token problem.
                return Boolean.FALSE;
            }
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, status, expires_at, email_binding, email_verified_at, credential_epoch"
                            + " FROM password_reset_challenges WHERE token_hash = ? FOR UPDATE", hash);
            if (rows.isEmpty()) {
                // Deleted concurrently (e.g. user cascade) — reject, never throw.
                return Boolean.FALSE;
            }
            Map<String, Object> row = rows.get(0);
            if (!"PENDING".equals(row.get("status"))) {
                return Boolean.FALSE;
            }
            Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
            if (!now.isBefore(((Timestamp) row.get("expires_at")).toInstant())) {
                return Boolean.FALSE;
            }
            boolean bound = user.getEmail().trim().equalsIgnoreCase(((String) row.get("email_binding")).trim())
                    && user.getEmailVerifiedAt() != null
                    && user.getEmailVerifiedAt().equals(((Timestamp) row.get("email_verified_at")).toInstant())
                    && user.getTokenVersion().equals(((Number) row.get("credential_epoch")).intValue())
                    && user.getLocalCredentialState() == LocalCredentialState.ENABLED;
            if (!bound) {
                return Boolean.FALSE;
            }
            // ACTIVE + temporary-lock check: a locked account throws 423 here and
            // the rollback leaves the challenge PENDING for a later retry.
            credentials.requireActiveUnlocked(user);
            if (passwords.matches(newPassword, user.getPasswordHash())) {
                // Current-password reuse is rejected without mutating anything.
                return Boolean.FALSE;
            }
            int taken = jdbc.update("UPDATE password_reset_challenges SET status = 'CONSUMED', consumed_at = ?"
                    + " WHERE id = ? AND status = 'PENDING'", Timestamp.from(now), row.get("id"));
            if (taken != 1) {
                return Boolean.FALSE;
            }
            // Bumps the epoch, revokes every refresh family, invalidates OAuth
            // proofs and — via AuthorizationProofsInvalidated — every other
            // pending reset challenge, all in this transaction.
            credentials.mutatePasswordLocked(user, newPassword, IdentityAuditEvent.PASSWORD_RESET);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(consumed);
    }

    /**
     * Synchronous {@link CredentialSecurityService.AuthorizationProofsInvalidated}
     * listener: password change/reset and admin email reassignment supersede
     * pending reset proofs in the same transaction (V23 extension point).
     */
    @EventListener
    public void onAuthorizationProofsInvalidated(CredentialSecurityService.AuthorizationProofsInvalidated event) {
        supersedePendingForUser(event.userId());
    }

    /** Terminalizes every PENDING challenge of the user (idempotent). */
    public void supersedePendingForUser(Long userId) {
        jdbc.update("UPDATE password_reset_challenges SET status = 'SUPERSEDED', superseded_at = ?"
                        + " WHERE user_id = ? AND status = 'PENDING'",
                Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)), userId);
    }

    /** 32 random bytes, Base64URL without padding (43 opaque characters). */
    public String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return URL_NO_PAD.encodeToString(bytes);
    }

    /** Domain-separated HMAC-SHA-256 of the raw token, 64 lowercase hex chars. */
    public String hashToken(String rawToken) {
        return hex(hmac(hmacKey, rawToken.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Trusted configured origin only; the fragment carries the opaque token. */
    public String resetUrl(String rawToken) {
        return resetBaseUri + "/reset-password#token=" + rawToken;
    }

    /**
     * Startup validation of the trusted reset origin (ADR-004): absolute
     * http(s) URI, no userinfo/query/fragment, origin-only path, HTTPS
     * required except explicitly non-production localhost/loopback HTTP.
     * The URL is never derived from Host or forwarded headers.
     */
    static String validatedBaseUri(String configured, boolean production) {
        String raw = configured == null ? "" : configured.trim();
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException ex) {
            uri = null;
        }
        String reason = null;
        if (uri == null || !uri.isAbsolute() || uri.getScheme() == null) {
            reason = "must be an absolute URI";
        } else if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            reason = "scheme must be http or https";
        } else if (uri.getUserInfo() != null) {
            reason = "userinfo is not allowed";
        } else if (uri.getQuery() != null || uri.getFragment() != null) {
            reason = "query and fragment are not allowed";
        } else if (uri.getHost() == null) {
            reason = "host is required";
        } else if (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
            reason = "must be an origin (no path)";
        } else if ("http".equals(uri.getScheme()) && (production || !isLoopback(uri.getHost()))) {
            reason = "http is only allowed for localhost/loopback in non-production";
        }
        if (reason != null) {
            throw new IllegalStateException(
                    "quizopia.password-reset.frontend-base-uri rejected at startup: " + reason);
        }
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private static boolean isLoopback(String host) {
        // Literal loopback names only — "127.0.0.1.evil.com" must not pass.
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static byte[] decodeKey(String keyBase64) {
        try {
            return Base64.getDecoder().decode(keyBase64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Data encryption key is not valid Base64", ex);
        }
    }

    static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(2 * digest.length);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
