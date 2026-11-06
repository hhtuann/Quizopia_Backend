package com.quizopia.backend.account.application;

import com.quizopia.backend.security.config.SecurityProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.quizopia.backend.account.application.PasswordResetService.hex;
import static com.quizopia.backend.account.application.PasswordResetService.hmac;

/**
 * Synchronous abuse admission for the public forgot-password endpoint
 * (ADR-004 §"Reset persistence, intake and delivery").
 *
 * <p>Fixed-order IP-then-email bucket admission runs in ONE short database
 * transaction for EVERY syntactically valid request — including unknown,
 * ineligible, throttled and saturated ones — before any account lookup or
 * SMTP. Buckets are keyed by a domain-separated HMAC-SHA-256 of the email or
 * requester IP (64 lowercase hex), so the table stores neither plaintext
 * addresses nor IPs. Never trust a forwarded IP; only the servlet-observed
 * remote address is keyed.
 *
 * <p>Each bucket row is upserted and locked: an elapsed one-hour window is
 * reset (preserving {@code last_admitted_at}) and attempts are
 * saturating-incremented even when the request is denied. {@code
 * last_admitted_at} advances only on overall admission, so denied attempts
 * never extend the cooldown. Fixed windows permit adjacent-boundary bursts,
 * bounded by both buckets and the cooldown.
 */
@Service
public class PasswordResetAbuseService {

    private static final String EMAIL_DOMAIN = "quizopia.abuse.email.v1";
    private static final String IP_DOMAIN = "quizopia.abuse.ip.v1";
    private static final String KEY_DOMAIN = "quizopia.abuse.hmac.v1";
    private static final int MAX_ATTEMPTS = Integer.MAX_VALUE;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PasswordResetProperties properties;
    private final byte[] hmacKey;

    public PasswordResetAbuseService(JdbcTemplate jdbc, Clock clock,
            PasswordResetProperties properties, SecurityProperties security) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        byte[] encryptionKey;
        try {
            encryptionKey = Base64.getDecoder().decode(security.getEncryption().getKeyBase64());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Data encryption key is not valid Base64", ex);
        }
        this.hmacKey = hmac(encryptionKey, KEY_DOMAIN.getBytes(StandardCharsets.UTF_8));
    }

    /** Bucket state after admission, for tests and observability. */
    public record BucketState(String key, Instant windowStart, int attempts, Instant lastAdmittedAt) {
    }

    /**
     * Admits (or denies) one forgot-password request. Must run inside the
     * caller's short transaction. Both buckets are always evaluated in fixed
     * order so the work per request is identical whether or not anything
     * denies it.
     *
     * @return {@code true} only when both buckets admit
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean admit(String email, String requestIp) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        // IP bucket first (skipped only when no remote address is observable).
        boolean ipAdmitted = true;
        String ipKey = null;
        if (requestIp != null && !requestIp.isBlank()) {
            ipKey = bucketKey(IP_DOMAIN, requestIp.trim());
            ipAdmitted = evaluate(ipKey, properties.getIpWindow(), properties.getMaxPerIp(), Duration.ZERO, now);
        }
        // Email bucket second.
        String emailKey = bucketKey(EMAIL_DOMAIN, normalizeEmail(email));
        boolean emailAdmitted = evaluate(
                emailKey, properties.getEmailWindow(), properties.getMaxPerEmail(), properties.getEmailCooldown(), now);
        boolean admitted = ipAdmitted && emailAdmitted;
        if (admitted) {
            // Only admitted requests extend the cooldown clock.
            Timestamp admittedAt = Timestamp.from(now);
            jdbc.update("UPDATE password_reset_abuse_buckets SET last_admitted_at = ? WHERE bucket_key = ?",
                    admittedAt, emailKey);
            if (ipKey != null) {
                jdbc.update("UPDATE password_reset_abuse_buckets SET last_admitted_at = ? WHERE bucket_key = ?",
                        admittedAt, ipKey);
            }
        }
        return admitted;
    }

    /** Test/observability view of the email bucket row (never used for decisions). */
    public BucketState emailBucket(String email) {
        return readBucket(bucketKey(EMAIL_DOMAIN, normalizeEmail(email)));
    }

    /**
     * Upserts, locks and advances one bucket. Attempts always
     * saturating-increment; a fully elapsed window resets with the attempt
     * count (and preserves last_admitted_at, so the cooldown survives window
     * boundaries).
     */
    private boolean evaluate(String key, Duration window, int max, Duration cooldown, Instant now) {
        jdbc.update("INSERT INTO password_reset_abuse_buckets"
                        + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
                        + " VALUES (?, ?, 0, NULL, ?) ON CONFLICT (bucket_key) DO NOTHING",
                key, Timestamp.from(now), Timestamp.from(now.plus(window)));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT window_start, attempts, last_admitted_at FROM password_reset_abuse_buckets"
                        + " WHERE bucket_key = ? FOR UPDATE", key);
        Map<String, Object> row = rows.get(0);
        Instant windowStart = ((Timestamp) row.get("window_start")).toInstant();
        int attempts = ((Number) row.get("attempts")).intValue();
        Timestamp lastAdmitted = (Timestamp) row.get("last_admitted_at");
        if (!now.isBefore(windowStart.plus(window))) {
            // Elapsed fixed window: reset the window; this request is attempt 1.
            windowStart = now;
            attempts = 0;
        }
        attempts = attempts == MAX_ATTEMPTS ? attempts : attempts + 1;
        jdbc.update("UPDATE password_reset_abuse_buckets SET window_start = ?, attempts = ?, expires_at = ?"
                        + " WHERE bucket_key = ?",
                Timestamp.from(windowStart), attempts, Timestamp.from(windowStart.plus(window)), key);
        boolean withinCap = attempts <= max;
        // A zero-cooldown bucket (IP) never consults last_admitted_at: there is
        // no cooldown to enforce, and an absolute-timestamp comparison would
        // wrongly deny every request after any backward clock adjustment.
        boolean cooldownElapsed = cooldown.isZero() || lastAdmitted == null
                || !now.isBefore(lastAdmitted.toInstant().plus(cooldown));
        return withinCap && cooldownElapsed;
    }

    private BucketState readBucket(String key) {
        return jdbc.query(
                "SELECT window_start, attempts, last_admitted_at FROM password_reset_abuse_buckets WHERE bucket_key = ?",
                (rs, i) -> new BucketState(key,
                        rs.getTimestamp("window_start").toInstant(),
                        rs.getInt("attempts"),
                        rs.getTimestamp("last_admitted_at") == null ? null
                                : rs.getTimestamp("last_admitted_at").toInstant()),
                key).stream().findFirst().orElse(null);
    }

    private String bucketKey(String domain, String value) {
        return hex(hmac(hmacKey, (domain + "|" + value).getBytes(StandardCharsets.UTF_8)));
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
