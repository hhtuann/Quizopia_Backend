package com.quizopia.backend.community.application;

import com.quizopia.backend.community.domain.model.CommunityRateBucket;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.community.repository.CommunityRateBucketRepository;
import com.quizopia.backend.security.config.SecurityProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;

/**
 * Reusable PostgreSQL-backed fixed-window admission for every community
 * write (ADR-006 §9), generalizing the proven V23
 * {@code PasswordResetAbuseService} mechanics: upsert
 * {@code ON CONFLICT (bucket_key) DO NOTHING} → {@code SELECT … FOR
 * UPDATE} → {@code UPDATE}, all inside the caller's transaction —
 * admission MUST be the first statement of every community write
 * transaction (before any exam/publication lock, so bucket locks stay
 * leaf-most in the §8 order).
 *
 * <p>Keys are domain-separated HMAC-SHA-256 digests of
 * {@code community:<dimension>:<policy>:<value>} over identities
 * {@code user:<id>} / {@code ip:<servlet-remote-addr>} (forwarded headers
 * are never trusted — V23 posture), so the table stores no plaintext
 * user ids or IPs. The HMAC key is derived from the same configured data
 * encryption key as V23, under a distinct key-domain string.
 *
 * <p><b>Fail closed</b>: any bucket-store failure propagates and fails
 * the request (no admission on doubt). Denied requests still consume
 * attempts (saturating); admission beyond the cap yields 429
 * COMMUNITY_RATE_LIMITED with {@code Retry-After} = seconds until the
 * current fixed window closes.
 */
@Component
public class CommunityRateLimiter {

    /** Key-derivation domain (distinct from the V23 abuse-key domain). */
    private static final String KEY_DOMAIN = "quizopia.community.ratelimit.hmac.v1";
    /** Message domain prefix: keys digest {@code community:<dimension>:<value>}. */
    private static final String MESSAGE_DOMAIN = "community";
    private static final String DIMENSION_USER = "user";
    private static final String DIMENSION_IP = "ip";
    private static final int MAX_ATTEMPTS = Integer.MAX_VALUE;

    private final CommunityRateBucketRepository buckets;
    private final CommunityRateLimitProperties properties;
    private final Clock clock;
    private final byte[] hmacKey;
    private final TransactionTemplate admissionTx;

    /** Named admission policies with thresholds from {@code quizopia.community.rate-limit.*}. */
    public enum Policy {
        CREATE_UPDATE,
        PUBLISH_UNPUBLISH,
        REPORT_USER,
        REPORT_IP,
        BOOKMARK,
        FORK,
        PUBLIC_READ_IP
    }

    public CommunityRateLimiter(CommunityRateBucketRepository buckets,
            CommunityRateLimitProperties properties,
            Clock clock,
            SecurityProperties security,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.buckets = buckets;
        this.properties = properties;
        this.clock = clock;
        this.admissionTx = new TransactionTemplate(transactionManager);
        this.admissionTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        byte[] encryptionKey;
        try {
            encryptionKey = Base64.getDecoder().decode(security.getEncryption().getKeyBase64());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Data encryption key is not valid Base64", ex);
        }
        this.hmacKey = hmac(encryptionKey, KEY_DOMAIN.getBytes(StandardCharsets.UTF_8));
    }

    /** Admits against the {@code user:<id>} bucket; 429 on saturation. */
    public void admitUser(Policy policy, Long userId) {
        admit(bucketKey(DIMENSION_USER, policy, "user:" + userId), maxFor(policy));
    }

    /**
     * Admits against the {@code ip:<remote-addr>} bucket; no-op when no
     * servlet-observed remote address exists. Never trusts forwarded
     * headers (V23 posture).
     */
    public void admitIp(Policy policy, String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return;
        }
        admit(bucketKey(DIMENSION_IP, policy, "ip:" + remoteAddr.trim()), maxFor(policy));
    }

    /** Test/observability view of a bucket key (never used for decisions). */
    public String userBucketKey(Policy policy, Long userId) {
        return bucketKey(DIMENSION_USER, policy, "user:" + userId);
    }

    public String ipBucketKey(Policy policy, String remoteAddr) {
        return bucketKey(DIMENSION_IP, policy, "ip:" + remoteAddr.trim());
    }

    private void admit(String bucketKey, int max) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Duration window = properties.getWindow();

        long retryAfterSeconds = admissionTx.execute(status -> {
            buckets.insertIfAbsent(bucketKey, now, now.plus(window));

            CommunityRateBucket bucket = buckets.findForUpdateByKey(bucketKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Rate bucket row missing after upsert: " + bucketKey));
            Instant windowStart = bucket.getWindowStart();
            int attempts = bucket.getAttempts();
            if (!now.isBefore(windowStart.plus(window))) {
                // Elapsed fixed window: reset; this request is attempt 1.
                windowStart = now;
                attempts = 0;
            }
            attempts = attempts == MAX_ATTEMPTS ? attempts : attempts + 1;
            Instant windowEnd = windowStart.plus(window);
            buckets.updateWindow(bucketKey, windowStart, attempts, windowEnd);
            if (attempts <= max) {
                buckets.markAdmitted(bucketKey, now);
                return 0L;
            }
            return Math.max(Duration.between(now, windowEnd).toSeconds(), 1);
        });
        if (retryAfterSeconds > 0) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_RATE_LIMITED, retryAfterSeconds);
        }
    }

    private int maxFor(Policy policy) {
        return switch (policy) {
            case CREATE_UPDATE -> properties.getCreateUpdateLimit();
            case PUBLISH_UNPUBLISH -> properties.getPublishUnpublishLimit();
            case REPORT_USER -> properties.getReportUserLimit();
            case REPORT_IP -> properties.getReportIpLimit();
            case BOOKMARK -> properties.getBookmarkLimit();
            case FORK -> properties.getForkLimit();
            case PUBLIC_READ_IP -> properties.getPublicReadIpLimit();
        };
    }

    /**
     * Domain-separated key: {@code community:<dimension>:<policy>:<value>}.
     * The policy segment is REQUIRED by the per-action thresholds (§9 gives
     * publish 10/h vs create 30/h for the same user) — one bucket per
     * subject alone could not carry two thresholds.
     */
    private String bucketKey(String dimension, Policy policy, String value) {
        return hex(hmac(hmacKey, (MESSAGE_DOMAIN + ":" + dimension + ":" + policy.name() + ":" + value)
                .getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", ex);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(2 * digest.length);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

}
