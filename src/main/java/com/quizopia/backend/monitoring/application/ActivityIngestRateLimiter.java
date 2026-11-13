package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.monitoring.domain.model.MonitoringRateBucket;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.monitoring.repository.MonitoringRateBucketRepository;
import com.quizopia.backend.security.config.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL fixed-window admission for activity-event ingestion (P4-A2,
 * issue #81 "rate"): same proven mechanics as {@code CommunityRateLimiter}
 * (V23 / ADR-006 §9), isolated in the monitoring schema.
 *
 * <p>Keys are domain-separated HMAC-SHA-256 digests of
 * {@code monitoring:ingest:user:<id>} over the configured data-encryption
 * key — the table stores no plaintext user ids. Admission runs in its own
 * REQUIRES_NEW transaction BEFORE any attempt read: it is a leaf lock that
 * never orders against the attempt/session lock matrix.
 *
 * <p><b>Fail closed:</b> any bucket-store failure propagates and fails the
 * request. Denied requests still consume attempts (saturating); admission
 * beyond the cap yields 429 with {@code Retry-After}.
 */
@Component
public class ActivityIngestRateLimiter {

    private static final String KEY_DOMAIN = "quizopia.monitoring.ratelimit.hmac.v1";
    private static final String MESSAGE_DOMAIN = "monitoring:ingest";
    private static final int MAX_ATTEMPTS = Integer.MAX_VALUE;

    private final MonitoringRateBucketRepository buckets;
    private final ActivityIngestProperties properties;
    private final Clock clock;
    private final byte[] hmacKey;
    private final TransactionTemplate admissionTx;

    public ActivityIngestRateLimiter(MonitoringRateBucketRepository buckets,
            ActivityIngestProperties properties, Clock clock,
            SecurityProperties security,
            PlatformTransactionManager transactionManager) {
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

    /** Admits one ingest batch for the user; throws 429 on saturation. */
    public void admitUser(Long userId) {
        String bucketKey = hex(hmac(hmacKey,
                (MESSAGE_DOMAIN + ":user:" + userId).getBytes(StandardCharsets.UTF_8)));
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Duration window = properties.getWindow();
        long retryAfterSeconds = admissionTx.execute(status -> {
            buckets.insertIfAbsent(bucketKey, now, now.plus(window));

            MonitoringRateBucket bucket = buckets.findForUpdateByKey(bucketKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Rate bucket row missing after upsert: " + bucketKey));
            Instant windowStart = bucket.getWindowStart();
            int attempts = bucket.getAttempts();
            if (!now.isBefore(windowStart.plus(window))) {
                windowStart = now;
                attempts = 0;
            }
            attempts = attempts == MAX_ATTEMPTS ? attempts : attempts + 1;
            Instant windowEnd = windowStart.plus(window);
            buckets.updateWindow(bucketKey, windowStart, attempts, windowEnd);
            if (attempts <= properties.getRateLimit()) {
                buckets.markAdmitted(bucketKey, now);
                return 0L;
            }
            return Math.max(Duration.between(now, windowEnd).toSeconds(), 1);
        });
        if (retryAfterSeconds > 0) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_RATE_LIMITED,
                    retryAfterSeconds);
        }
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
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
