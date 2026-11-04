package com.quizopia.backend.notification.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded background worker for the OTP RESEND flow (reviews R3-M1-03 /
 * round-4). The public resend endpoint performs NO account-specific work: it
 * runs one constant HMAC and enqueues the request here. This executor is the
 * single bounded intake/processing path for resend.
 *
 * <h2>Bounded capacity & rejection (documented tradeoff)</h2>
 * The queue is bounded ({@code quizopia.email-verification.resend-intake-capacity},
 * default 200). When the queue is full the intake is DROPPED with a WARN log
 * (no PII) and a metric-friendly message; the HTTP response stays uniform
 * 200. This is deliberate: an unbounded queue would allow memory growth under
 * public abuse, and blocking the HTTP thread would reintroduce state-dependent
 * latency. Recovery: the resend endpoint IS the recovery flow — a user whose
 * request was dropped simply retries after the cooldown window. Mail delivery
 * itself remains best-effort with the same recovery semantics (challenge row
 * exists, {@code delivered=false}; a later resend issues a fresh challenge).
 *
 * <h2>Shutdown</h2>
 * {@code shutdown()} + up to 30s drain on context close; tasks not drained
 * are lost with the same recovery semantics as an SMTP outage. Tasks are not
 * durable across restarts (documented; a restart mid-cooldown costs the user
 * one resend — the challenge row and cooldown state live in PostgreSQL and
 * survive).
 */
@Configuration
public class OtpDeliveryConfig {

    private static final int WORKER_THREADS = 2;

    @Bean(destroyMethod = "shutdown")
    public ExecutorService otpDeliveryExecutor(
            @Value("${quizopia.email-verification.resend-intake-capacity:200}")
            int intakeCapacity) {
        return new ThreadPoolExecutor(
                WORKER_THREADS, WORKER_THREADS,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, intakeCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "otp-resend-worker");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
