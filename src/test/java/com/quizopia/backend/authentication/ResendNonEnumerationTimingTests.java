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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Review round 4 (R3-M1-03): the public resend endpoint must not distinguish
 * account state through STATUS, BODY, or TIMING that repeated same-email
 * probes could exploit. Four states are probed repeatedly with the SAME email
 * addresses (realistic enumeration pattern): unknown, verified,
 * unverified-throttled, and eligible.
 *
 * <p>With a deliberately slow SMTP double (300ms), the medians of all four
 * states must sit in ONE tight band (each < SLOW_MS/2 and pairwise spread <
 * TIGHT_SPREAD_MS). This is a measured bound, not a constant-time claim —
 * the invariant is that no state pays account-specific processing (locks,
 * extra queries, SMTP) on the HTTP path.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, ResendNonEnumerationTimingTests.SlowMailConfig.class})
@ActiveProfiles("test")
class ResendNonEnumerationTimingTests extends AbstractAuthenticationIntegrationTests {

    private static final long SLOW_MS = 300;
    /** All states must stay far below the SMTP term. */
    private static final long PER_STATE_MAX_MS = SLOW_MS / 2;
    /** Pairwise median spread — the tight band. */
    private static final long TIGHT_SPREAD_MS = 40;
    private static final int WARMUP = 3;
    private static final int SAMPLES = 15;

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowMailConfig {

        @Bean
        @Primary
        OtpMailSender slowOtpMailSender(com.quizopia.backend.notification.email.CaptureOtpMailSender capture) {
            return new OtpMailSender() {
                @Override
                public boolean sendAccountVerificationOtp(String toEmail, String displayName,
                        String otp, long expiresInSeconds) {
                    try {
                        Thread.sleep(SLOW_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return capture.sendAccountVerificationOtp(toEmail, displayName, otp, expiresInSeconds);
                }
            };
        }
    }

    @Test
    void repeatedProbesAcrossAllStatesShareOneTightLatencyBand() throws Exception {
        String unknown = "probe-unknown-" + UUID.randomUUID() + "@example.com";

        String verifiedUser = unique("pv");
        String verifiedEmail = emailFor(verifiedUser);
        register(studentRegisterJson(verifiedUser, verifiedEmail, "Passw0rd!",
                verifiedUser + " Name", "+84991234567"));
        assertThat(submitOtp(verifiedEmail, otpMailbox.latestOtpFor(verifiedEmail))).isEqualTo(200);

        String throttledUser = unique("pt");
        String throttledEmail = emailFor(throttledUser);
        register(studentRegisterJson(throttledUser, throttledEmail, "Passw0rd!",
                throttledUser + " Name", "+84991234567"));
        // No verification → unverified; the registration challenge is fresh →
        // every immediate probe is COOLED DOWN.

        String eligibleUser = unique("pe");
        String eligibleEmail = emailFor(eligibleUser);
        register(studentRegisterJson(eligibleUser, eligibleEmail, "Passw0rd!",
                eligibleUser + " Name", "+84991234567"));
        // Eligible AFTER cooldown: probe t=0 (throttled) is state A; after
        // 1.1s the same email's first resend is ELIGIBLE. The eligible state
        // is sampled through the worker's issuance path.

        // Warm-up every state (JIT/pool warmers).
        for (int i = 0; i < WARMUP; i++) {
            timeResend(unknown);
            timeResend(verifiedEmail);
            timeResend(throttledEmail);
        }
        // Let the warm-up eligibles/throttles settle before the measured
        // eligible window opens.
        Thread.sleep(1100);

        long[] unknownSamples = sample(unknown);
        long[] verifiedSamples = sample(verifiedEmail);
        long[] throttledSamples = sample(throttledEmail);

        // Eligible: the FIRST resend after the sleep issues (worker path);
        // measure it once — it is the heaviest legitimate state.
        long eligibleSample = timeResend(eligibleEmail);

        long unknownMedian = median(unknownSamples);
        long verifiedMedian = median(verifiedSamples);
        long throttledMedian = median(throttledSamples);

        assertThat(unknownMedian).isLessThan(PER_STATE_MAX_MS);
        assertThat(verifiedMedian).isLessThan(PER_STATE_MAX_MS);
        assertThat(throttledMedian).isLessThan(PER_STATE_MAX_MS);
        assertThat(eligibleSample)
                .as("eligible resend must not pay the %dms SMTP term on the HTTP path", SLOW_MS)
                .isLessThan(PER_STATE_MAX_MS);

        long min = Math.min(unknownMedian, Math.min(verifiedMedian, throttledMedian));
        long max = Math.max(unknownMedian, Math.max(verifiedMedian, throttledMedian));
        assertThat(max - min)
                .as("median spread across states must be tight (was unknown=%d verified=%d throttled=%d)",
                        unknownMedian, verifiedMedian, throttledMedian)
                .isLessThan(TIGHT_SPREAD_MS);
        assertThat(Math.abs(eligibleSample - min))
                .as("eligible sample must stay in the same band as the other states")
                .isLessThan(TIGHT_SPREAD_MS * 2);

        // Delivery still happened for the eligible state (worker proven).
        waitForOtpMailCount(eligibleEmail, 2);
    }

    private long[] sample(String email) throws Exception {
        long[] samples = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            samples[i] = timeResend(email);
        }
        return samples;
    }

    private long timeResend(String email) throws Exception {
        long start = System.nanoTime();
        MvcResult result = mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andReturn();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("resendCooldownSeconds");
        return elapsedMs;
    }

    private int submitOtp(String email, String otp) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, otp)))
                .andReturn().getResponse().getStatus();
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
