package com.quizopia.backend.authentication.application;

import com.quizopia.backend.identity.repository.EmailVerificationChallengeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Periodic cleanup of email-verification challenges (ADR-002 §5): PENDING
 * challenges past their expiry are marked FAILED and terminal challenges
 * older than the retention window are deleted. Disabled in tests so it can
 * never race with a test's clock manipulation (mirrors the attempt-timeout
 * sweeper pattern).
 */
@Component
@ConditionalOnProperty(
        value = "quizopia.email-verification.cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EmailVerificationCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationCleanupScheduler.class);

    private final EmailVerificationChallengeRepository challengeRepository;
    private final EmailVerificationProperties properties;
    private final Clock clock;

    public EmailVerificationCleanupScheduler(EmailVerificationChallengeRepository challengeRepository,
            EmailVerificationProperties properties, Clock clock) {
        this.challengeRepository = challengeRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${quizopia.email-verification.cleanup.interval:PT1H}",
            initialDelayString = "${quizopia.email-verification.cleanup.interval:PT1H}")
    @Transactional
    public void sweep() {
        Instant now = Instant.now(clock);
        int expired = challengeRepository.failExpiredPending(now);
        int deleted = challengeRepository.deleteTerminalOlderThan(now.minus(properties.getCleanup().getRetention()));
        if (expired > 0 || deleted > 0) {
            log.info("Email verification cleanup: {} expired challenges failed, {} terminal challenges deleted",
                    expired, deleted);
        }
    }
}
