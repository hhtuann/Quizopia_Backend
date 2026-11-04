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
        DeliveryCompletionRaceIntegrationTests.SlowMailConfig.class})
@ActiveProfiles("test")
class DeliveryCompletionRaceIntegrationTests extends AbstractAuthenticationIntegrationTests {

    /** Delays delivery so the racing verify lands while "SMTP" is running. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SlowMailConfig {

        static volatile long delayMs = 0;

        @Bean
        @Primary
        OtpMailSender slowOtpMailSender(com.quizopia.backend.notification.email.CaptureOtpMailSender capture) {
            return new OtpMailSender() {
                @Override
                public boolean sendAccountVerificationOtp(String toEmail, String displayName,
                        String otp, long expiresInSeconds) {
                    long delay = delayMs;
                    if (delay > 0) {
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return capture.sendAccountVerificationOtp(toEmail, displayName, otp, expiresInSeconds);
                }
            };
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
        SlowMailConfig.delayMs = 1500;
        try {
            mockMvc.perform(post("/api/auth/verify-email/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\"}".formatted(email)))
                    .andReturn().getResponse().getStatus();
            waitForChallengeCount(userId, 2); // C2 exists (PENDING)

            String wrong = realOtp.equals("123456") ? "654321" : "123456";
            int status = mockMvc.perform(post("/api/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(verifyEmailJson(email, wrong)))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(400);
            waitForAttempts(userId, 1); // counter committed while delivery in flight

            Thread.sleep(1800); // let the delayed delivery write land
        } finally {
            SlowMailConfig.delayMs = 0;
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
