package com.quizopia.backend.authentication;

import com.quizopia.backend.notification.email.OtpMailSender;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Review round 4 (operational follow-up): the bounded resend intake must
 * behave safely under worker saturation, delivery failure and recovery —
 * uniform 200s throughout, no exception surfaced, no unbounded growth, and a
 * functioning recovery path once workers drain.
 */
@SpringBootTest(properties = "quizopia.email-verification.resend-intake-capacity=4")
@Import({PostgresTestContainerConfiguration.class, ResendWorkerOverloadTests.BlockingMailConfig.class})
@ActiveProfiles("test")
class ResendWorkerOverloadTests extends AbstractAuthenticationIntegrationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class BlockingMailConfig {

        static final java.util.concurrent.atomic.AtomicBoolean failing =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        /**
         * SMTP double: optionally SLOW (400ms, saturates the workers for the
         * overload test) and optionally FAILING (returns false, never
         * records) — a true delivery-failure injection.
         */
        @Bean
        @Primary
        OtpMailSender blockingOtpMailSender(com.quizopia.backend.notification.email.CaptureOtpMailSender capture) {
            return new OtpMailSender() {
                @Override
                public boolean sendAccountVerificationOtp(String toEmail, String displayName,
                        String otp, long expiresInSeconds) {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (failing.get()) {
                        return false;
                    }
                    return capture.sendAccountVerificationOtp(toEmail, displayName, otp, expiresInSeconds);
                }
            };
        }
    }

    @Test
    void saturatedQueueDropsIntakeUniformlyAndRecovers() throws Exception {
        String user = unique("ovl");
        String email = emailFor(user);
        register(studentRegisterJson(user, email, "Passw0rd!", user + " Name", "+84991234567"));

        // Saturate: 2 workers × 400ms each + queue capacity 4 ⇒ beyond ~6
        // rapid intakes, further intakes are dropped — every response must
        // still be the SAME uniform 200.
        int probes = 30;
        AtomicInteger uniform200 = new AtomicInteger();
        for (int i = 0; i < probes; i++) {
            MvcResult result = mockMvc.perform(post("/api/auth/verify-email/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\"}".formatted(email)))
                    .andReturn();
            if (result.getResponse().getStatus() == 200
                    && result.getResponse().getContentAsString().contains("resendCooldownSeconds")) {
                uniform200.incrementAndGet();
            }
        }
        assertThat(uniform200.get()).as("every intake answers the uniform 200 body").isEqualTo(probes);

        // Workers drain; delivery of whatever was accepted still happens
        // (bounded: at most capacity + in-flight accepted tasks). Recovery:
        // after draining, a fresh resend intake is processed again.
        Thread.sleep(3000);
        int before = otpMailbox.sendCountFor(email);
        Thread.sleep(1100); // cooldown
        MvcResult recovery = mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andReturn();
        assertThat(recovery.getResponse().getStatus()).isEqualTo(200);
        waitForOtpMailCount(email, before + 1);
    }

    @Test
    void failingSmtpDeliveryNeverThrowsAndChallengeStaysRecoverable() throws Exception {
        String user = unique("fail");
        String email = emailFor(user);
        register(studentRegisterJson(user, email, "Passw0rd!", user + " Name", "+84991234567"));
        waitForOtpMailCount(email, 1); // registration mail delivered normally

        // Install a FAILING sender, then drive a resend through the real
        // worker: no exception may surface (intake stays uniform 200) and the
        // challenge row must remain with delivered=false. (Cooldown first:
        // the registration challenge is fresh.)
        Thread.sleep(1100);
        BlockingMailConfig.failing.set(true);
        try {
            MvcResult result = mockMvc.perform(post("/api/auth/verify-email/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\"}".formatted(email)))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).contains("resendCooldownSeconds");
            waitForChallengeDeliveredFlag(user, false);
            Thread.sleep(1100); // cooldown before recovery
        } finally {
            BlockingMailConfig.failing.set(false);
        }

        // Recovery: a later resend (healthy sender) issues a fresh challenge
        // that IS delivered.
        MvcResult recovery = mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andReturn();
        assertThat(recovery.getResponse().getStatus()).isEqualTo(200);
        // Registration + recovered delivery. The FAILED send never reached
        // the capture sender (it returned false before recording), so the
        // expected total is 2 — proving the failed path produced no mail.
        waitForOtpMailCount(email, 2);
        waitForChallengeDeliveredFlag(user, true);
    }

    private void waitForChallengeDeliveredFlag(String username, boolean delivered) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Boolean flag = jdbc.queryForObject(
                    "SELECT c.delivered FROM email_verification_challenges c"
                            + " JOIN users u ON u.id = c.user_id WHERE u.username = ?"
                            + " ORDER BY c.id DESC LIMIT 1", Boolean.class, username);
            if (flag != null && flag == delivered) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("latest challenge delivered flag did not become " + delivered);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private int challengeCount(String username) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM email_verification_challenges c"
                        + " JOIN users u ON u.id = c.user_id WHERE u.username = ?",
                Integer.class, username);
    }

    private int countViaJdbc(String username) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM email_verification_challenges c"
                        + " JOIN users u ON u.id = c.user_id WHERE u.username = ?",
                Integer.class, username);
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
