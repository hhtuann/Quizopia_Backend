package com.quizopia.backend.account.config;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded background intake for the public forgot-password flow
 * (ADR-004 §"Reset persistence, intake and delivery"). The HTTP path only
 * performs the synchronous abuse admission; admitted work is enqueued here
 * (2 workers, capacity {@code quizopia.password-reset.intake-capacity},
 * default 200). This executor is the single bounded processing path — the
 * queue receives ONLY admitted work, and when it is full the intake is
 * dropped with a WARN log (no PII) while the admission allowance is still
 * consumed, so the HTTP response stays identical 202. Recovery: the user
 * retries after the cooldown. Delivery remains best-effort; challenges and
 * bucket state live in PostgreSQL and survive restarts, in-flight tasks do
 * not (documented tradeoff — a restart mid-flight costs one notification).
 */
@Configuration
public class PasswordResetIntakeConfig {

    private static final int WORKER_THREADS = 2;
    private static final long DRAIN_TIMEOUT_SECONDS = 30;

    private ExecutorService executor;

    @Bean
    public ExecutorService passwordResetIntakeExecutor(
            @Value("${quizopia.password-reset.intake-capacity:200}") int intakeCapacity) {
        executor = new ThreadPoolExecutor(
                WORKER_THREADS, WORKER_THREADS,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, intakeCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "password-reset-worker");
                    thread.setDaemon(true);
                    return thread;
                });
        return executor;
    }

    /**
     * Real awaited drain: stop accepting new tasks, then wait up to 30s for
     * queued/in-flight issuances to finish so a restart during ordinary
     * traffic does not drop accepted work.
     */
    @PreDestroy
    void shutdownAndDrain() throws InterruptedException {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        if (!executor.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
