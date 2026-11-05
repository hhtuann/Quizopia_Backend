package com.quizopia.backend.authentication;

import com.quizopia.backend.notification.email.OtpMailSender;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Reviewer blocker regression: delivery completion must never overwrite
 * concurrent lifecycle changes on the challenge row. SMTP runs outside the
 * transaction; while it runs, ANOTHER request may consume or fail the same
 * challenge. The delivered-flag write must be a targeted update that leaves
 * attempts/terminal-state/supersession intact.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class,
        DeliveryCompletionRaceIntegrationTests.GatedMailConfig.class})
@ActiveProfiles("test")
class DeliveryCompletionRaceIntegrationTests extends AbstractAuthenticationIntegrationTests {

    /**
     * Deterministic SMTP double for the delivery race: when armed, the send
     * records its OTP, signals "entered" and then BLOCKS on the release
     * gate — the racing verify is guaranteed to commit while the send is in
     * flight (no sleeps ordering the race).
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class GatedMailConfig {

        static volatile boolean armed = false;
        static final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        static volatile String inFlightOtp;
        static volatile java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);

        @Bean
        @Primary
        OtpMailSender gatedOtpMailSender(com.quizopia.backend.notification.email.CaptureOtpMailSender capture) {
            return new OtpMailSender() {
                @Override
                public boolean sendAccountVerificationOtp(String toEmail, String displayName,
                        String otp, long expiresInSeconds) {
                    if (!armed) {
                        return capture.sendAccountVerificationOtp(toEmail, displayName, otp, expiresInSeconds);
                    }
                    inFlightOtp = otp;
                    entered.countDown();
                    try {
                        // Block until the racing attempt has committed.
                        if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                            return false;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    return capture.sendAccountVerificationOtp(toEmail, displayName, otp, expiresInSeconds);
                }
            };
        }

        static void rearm() {
            armed = true;
            inFlightOtp = null;
            release = new java.util.concurrent.CountDownLatch(1);
        }

        static void disarm() {
            armed = false;
            release.countDown();
        }
    }

    @Autowired
    private EntityManager entityManager;

    @Test
    void deliveredFlagWriteCannotResetConcurrentAttemptCounter() throws Exception {
        String username = unique("dcr");
        String email = emailFor(username);
        // Registration delivery: capture immediately (no delay).
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        String realOtp = otpMailbox.latestOtpFor(email);
        Long userId = userIdOf(username);

        // Resend with DELAYED delivery: the worker issues challenge C2 and
        // starts "sending" (1500ms). While that is in flight, the user
        // submits a wrong OTP (e.g. from a stale email) → C2's failed-attempt
        // counter commits at 1. The later delivered-flag write must NOT reset
        // that counter (the stale full-entity merge would).
        Thread.sleep(1100); // clear the 1s test cooldown
        GatedMailConfig.rearm();
        try {
            mockMvc.perform(post("/api/auth/verify-email/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\"}".formatted(email)))
                    .andReturn().getResponse().getStatus();
            waitForChallengeCount(userId, 2); // C2 exists (PENDING)
            // The send has ENTERED (blocked on the gate) — the race window is
            // open, and the in-flight OTP is captured deterministically.
            assertThat(GatedMailConfig.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("delayed send must be in flight").isTrue();
            String c2Otp = GatedMailConfig.inFlightOtp;
            assertThat(c2Otp).isNotEqualTo(realOtp);

            String wrong = c2Otp.equals("123456") ? "654321" : "123456";
            int status = mockMvc.perform(post("/api/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(verifyEmailJson(email, wrong)))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(400);
            waitForAttempts(userId, 1); // counter committed while delivery in flight

            // NOW release the blocked send; the delivered-flag write lands
            // strictly after the attempt commit.
            GatedMailConfig.release.countDown();
            waitForDelivered(userId, true);
        } finally {
            GatedMailConfig.disarm();
        }

        entityManager.clear();
        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT c.failed_attempts, c.delivered, c.status"
                                + " FROM email_verification_challenges c"
                                + " WHERE c.user_id = :u ORDER BY c.id DESC LIMIT 1")
                .setParameter("u", userId)
                .getSingleResult();
        assertThat(((Number) row[0]).intValue())
                .as("targeted update must not reset the committed attempt counter")
                .isEqualTo(1);
        assertThat(row[1]).as("delivered flag recorded").isEqualTo(Boolean.TRUE);
        assertThat(row[2]).as("challenge remains PENDING (recoverable)").isEqualTo("PENDING");
    }

    private void waitForAttempts(Long userId, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            entityManager.clear();
            int attempts = ((Number) entityManager.createNativeQuery(
                            "SELECT c.failed_attempts FROM email_verification_challenges c"
                                    + " WHERE c.user_id = :u ORDER BY c.id DESC LIMIT 1")
                    .setParameter("u", userId)
                    .getSingleResult()).intValue();
            if (attempts >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("attempt counter did not reach " + expected);
    }

    private void waitForDelivered(Long userId, boolean delivered) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            entityManager.clear();
            Boolean flag = (Boolean) entityManager.createNativeQuery(
                            "SELECT c.delivered FROM email_verification_challenges c"
                                    + " WHERE c.user_id = :u ORDER BY c.id DESC LIMIT 1")
                    .setParameter("u", userId)
                    .getSingleResult();
            if (flag != null && flag == delivered) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("delivered flag did not become " + delivered);
    }

    private void waitForChallengeCount(Long userId, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            int count = ((Number) entityManager.createNativeQuery(
                            "SELECT count(*) FROM email_verification_challenges WHERE user_id = :u")
                    .setParameter("u", userId)
                    .getSingleResult()).intValue();
            if (count >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("expected >= " + expected + " challenges");
    }

    private Long userIdOf(String username) {
        entityManager.clear();
        return (Long) entityManager.createNativeQuery(
                        "SELECT id FROM users WHERE username = :u")
                .setParameter("u", username)
                .getSingleResult();
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
