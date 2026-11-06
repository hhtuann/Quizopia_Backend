package com.quizopia.backend.account.application;

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
 * Periodic cleanup of password-reset persistence (ADR-004): challenge rows
 * expired more than seven days ago are deleted, and abuse buckets are
 * deleted two hours after their window expiry. Disabled in tests so it can
 * never race with a test's clock manipulation (mirrors the OTP cleanup
 * sweeper pattern).
 */
@Component
@ConditionalOnProperty(
        value = "quizopia.password-reset.cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PasswordResetCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetCleanupScheduler.class);

    private final JdbcTemplate jdbc;
    private final PasswordResetProperties properties;
    private final Clock clock;

    public PasswordResetCleanupScheduler(JdbcTemplate jdbc, PasswordResetProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${quizopia.password-reset.cleanup.interval:PT1H}",
            initialDelayString = "${quizopia.password-reset.cleanup.interval:PT1H}")
    public void sweep() {
        Instant now = Instant.now(clock);
        int challenges = jdbc.update("DELETE FROM password_reset_challenges WHERE expires_at < ?",
                Timestamp.from(now.minus(properties.getChallengeRetention())));
        int buckets = jdbc.update("DELETE FROM password_reset_abuse_buckets WHERE expires_at < ?",
                Timestamp.from(now.minus(properties.getBucketRetention())));
        if (challenges > 0 || buckets > 0) {
            log.info("Password reset cleanup: {} expired challenges deleted, {} expired abuse buckets deleted",
                    challenges, buckets);
        }
    }
}
