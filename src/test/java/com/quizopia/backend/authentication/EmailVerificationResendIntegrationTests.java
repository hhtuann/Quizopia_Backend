package com.quizopia.backend.authentication;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Resend behaviour against the ASYNC bounded worker (round 4). Deliberately
 * NOT {@code @Transactional}: the background worker runs on its own
 * connection and observes only COMMITTED state — exactly like production —
 * so every test registers a real committed account first and waits for the
 * worker's observable effects (mail / challenge counts) instead of assuming
 * synchronous completion.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
class EmailVerificationResendIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private MutableClock clock;

    @Autowired
    private EntityManager entityManager;

    @Test
    void cooldownWindowIssuesNothingButAnswersUniformly() throws Exception {
        String username = unique("rc");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));

        MvcResult immediate = resend(email);
        assertThat(immediate.getResponse().getStatus()).isEqualTo(200);
        assertThat(immediate.getResponse().getContentAsString()).contains("resendCooldownSeconds");
        // The worker processes and throttles; counts stay at one.
        waitForWorkerToSettle();
        assertThat(challengeCount(username)).isEqualTo(1);
        assertThat(otpMailbox.sendCountFor(email)).isEqualTo(1);
    }

    @Test
    void resendAfterCooldownIssuesNewChallengeAndKillsOldCode() throws Exception {
        String username = unique("rs");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        String oldOtp = otpMailbox.latestOtpFor(email);

        clock.advance(java.time.Duration.ofSeconds(2)); // clear 1s test cooldown
        assertThat(resend(email).getResponse().getStatus()).isEqualTo(200);
        waitForOtpMailCount(email, 2);
        String freshOtp = otpMailbox.latestOtpFor(email);
        assertThat(freshOtp).isNotEqualTo(oldOtp);

        entityManager.clear();
        assertThat(challengeCount(username)).isEqualTo(2);
        // The superseded code no longer verifies; the fresh one does.
        assertThat(submitOtp(email, oldOtp)).isEqualTo(400);
        assertThat(submitOtp(email, freshOtp)).isEqualTo(200);
    }

    @Test
    void expiredChallengeRecoversViaResend() throws Exception {
        String username = unique("rx");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        clock.advance(java.time.Duration.ofMinutes(11)); // OTP TTL 10m

        assertThat(submitOtp(email, otpMailbox.latestOtpFor(email))).isEqualTo(400);

        clock.advance(java.time.Duration.ofSeconds(2));
        assertThat(resend(email).getResponse().getStatus()).isEqualTo(200);
        waitForOtpMailCount(email, 2);
        assertThat(submitOtp(email, otpMailbox.latestOtpFor(email))).isEqualTo(200);
    }

    @Test
    void perEmailCapThrottlesAfterFiveChallengesPerHour() throws Exception {
        String username = unique("rr");
        String email = emailFor(username);
        clock.setInstant(java.time.Instant.now());
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));

        // 1 registration + 4 eligible resends reach the cap of 5; the 5th
        // resend is throttled by the window. Each intake waits for the
        // worker's observable effect before advancing the clock (the worker
        // serializes per-account on the user-row lock).
        for (int i = 0; i < 4; i++) {
            clock.setInstant(java.time.Instant.now().plusSeconds(2 + i));
            assertThat(resend(email).getResponse().getStatus()).isEqualTo(200);
            waitForOtpMailCount(email, 2 + i);
        }
        clock.setInstant(java.time.Instant.now().plusSeconds(7));
        assertThat(resend(email).getResponse().getStatus()).isEqualTo(200);
        waitForWorkerToSettle();
        entityManager.clear();
        assertThat(challengeCount(username)).isEqualTo(5); // no sixth challenge
        assertThat(otpMailbox.sendCountFor(email)).isEqualTo(5); // no sixth mail
    }

    @Test
    void unknownAndVerifiedEmailsGetIdenticalResponsesAndNeverReceiveMail() throws Exception {
        String unknownEmail = "ghost-" + UUID.randomUUID() + "@example.com";
        MvcResult unknown = resend(unknownEmail);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(200);

        String username = unique("rv");
        String verifiedEmail = emailFor(username);
        register(studentRegisterJson(username, verifiedEmail, "Passw0rd!", username + " Name", "+84991234567"));
        assertThat(submitOtp(verifiedEmail, otpMailbox.latestOtpFor(verifiedEmail))).isEqualTo(200);
        int mailsBefore = otpMailbox.sendCountFor(verifiedEmail);

        MvcResult verified = resend(verifiedEmail);
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        // Bodies identical (constant cooldown — no account-derived data).
        JsonNode unknownBody = objectMapper.readTree(unknown.getResponse().getContentAsString());
        JsonNode verifiedBody = objectMapper.readTree(verified.getResponse().getContentAsString());
        assertThat(verifiedBody).isEqualTo(unknownBody);
        waitForWorkerToSettle();
        assertThat(otpMailbox.sendCountFor(verifiedEmail)).isEqualTo(mailsBefore);
        assertThat(otpMailbox.sendCountFor(unknownEmail)).isZero();
    }

    /** 8 parallel resends inside one cooldown window issue exactly ONE. */
    @Test
    void concurrentResendsIssueExactlyOnePerEligibleWindow() throws Exception {
        String username = unique("rw");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> resend(email).getResponse().getStatus()));
        }
        for (var f : futures) {
            assertThat(f.get()).isEqualTo(200);
        }
        pool.shutdownNow();
        waitForWorkerToSettle();
        entityManager.clear();
        assertThat(challengeCount(username)).isEqualTo(1); // all throttled
        assertThat(otpMailbox.sendCountFor(email)).isEqualTo(1);

        // After the cooldown (MutableClock — advance, not sleep), a single
        // resend issues exactly one more.
        clock.advance(java.time.Duration.ofSeconds(2));
        assertThat(resend(email).getResponse().getStatus()).isEqualTo(200);
        waitForOtpMailCount(email, 2);
        entityManager.clear();
        assertThat(challengeCount(username)).isEqualTo(2);
    }

    // ---------- helpers ----------

    private MvcResult resend(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andReturn();
    }

    private int submitOtp(String email, String otp) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, otp)))
                .andReturn().getResponse().getStatus();
    }

    private int challengeCount(String username) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM email_verification_challenges c"
                        + " JOIN users u ON u.id = c.user_id WHERE u.username = :u")
                .setParameter("u", username)
                .getSingleResult()).intValue();
    }

    /**
     * Waits until the worker has fully drained: poll the queue depth of the
     * bounded executor until zero, then a short grace for in-flight tasks.
     */
    private void waitForWorkerToSettle() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (resendQueueDepth() == 0 && activeResendWorkers() == 0) {
                Thread.sleep(150); // in-flight grace
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("resend worker did not settle within 10s");
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    private int resendQueueDepth() {
        Object executor = applicationContext.getBean("otpDeliveryExecutor");
        if (executor instanceof java.util.concurrent.ThreadPoolExecutor tpe) {
            return tpe.getQueue().size();
        }
        return 0;
    }

    private int activeResendWorkers() {
        Object executor = applicationContext.getBean("otpDeliveryExecutor");
        if (executor instanceof java.util.concurrent.ThreadPoolExecutor tpe) {
            return tpe.getActiveCount();
        }
        return 0;
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
