package com.quizopia.backend.community.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * Periodic cleanup of {@code community_rate_buckets} (ADR-006 §3/§9):
 * rows whose window expired more than {@code bucket-retention} (default
 * 2h) ago are deleted. Mirrors {@code PasswordResetCleanupScheduler};
 * disabled in tests so it can never race with clock manipulation.
 */
@Component
@ConditionalOnProperty(
        value = "quizopia.community.rate-limit.cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CommunityRateBucketCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CommunityRateBucketCleanupScheduler.class);

    private final JdbcTemplate jdbc;
    private final CommunityRateLimitProperties properties;
    private final Clock clock;

    public CommunityRateBucketCleanupScheduler(JdbcTemplate jdbc,
            CommunityRateLimitProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${quizopia.community.rate-limit.cleanup-interval:PT1H}",
            initialDelayString = "${quizopia.community.rate-limit.cleanup-interval:PT1H}")
    public void sweep() {
        Instant cutoff = Instant.now(clock).minus(properties.getBucketRetention());
        int deleted = jdbc.update("DELETE FROM community_rate_buckets WHERE expires_at < ?",
                Timestamp.from(cutoff));
        if (deleted > 0) {
            log.info("Community rate-bucket cleanup: {} expired buckets deleted", deleted);
        }
    }
}
