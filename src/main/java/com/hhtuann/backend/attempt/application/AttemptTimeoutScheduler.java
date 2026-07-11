package com.hhtuann.backend.attempt.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodic sweeper that finalizes IN_PROGRESS attempts whose deadline has
 * passed. The client-side auto-submit (the AttemptShell timer) only fires while
 * the student is on the attempt page; this job is the server-side backstop for
 * students who left before the timer hit 0, so an attempt is always recorded as
 * SUBMITTED at its deadline — never left IN_PROGRESS until the student happens
 * to open it again (which previously recorded the wrong submit time = click
 * time, and a duration larger than the exam's limit).
 *
 * <p>Each attempt is finalized in its own transaction (via the
 * {@link AttemptSubmitService} proxy) so one grading failure cannot roll back
 * the rest; failures are logged and retried on the next sweep. The first
 * execution runs on startup (no initial delay), so any stale IN_PROGRESS
 * attempts left over from before the job existed are cleaned up immediately.
 */
@Component
@ConditionalOnProperty(
        name = "quizopia.attempt.timeout-sweep.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AttemptTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttemptTimeoutScheduler.class);
    /** 30s between sweeps — short enough that a student reloading /attempts sees
     *  the finalized state without a noticeable wait. */
    private static final long FIXED_DELAY_MS = 30_000L;

    private final AttemptSubmitService submitService;

    public AttemptTimeoutScheduler(AttemptSubmitService submitService) {
        this.submitService = submitService;
    }

    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void sweepExpiredAttempts() {
        List<Long> ids;
        try {
            ids = submitService.findExpiredAttemptIds();
        } catch (Exception e) {
            log.warn("failed to list expired attempts for sweep", e);
            return;
        }
        if (ids.isEmpty()) {
            return;
        }
        for (Long id : ids) {
            try {
                submitService.finalizeAttempt(id);
            } catch (Exception e) {
                // One bad attempt must not abort the rest; it'll be retried next sweep.
                log.warn("failed to finalize expired attempt {}", id, e);
            }
        }
    }
}
