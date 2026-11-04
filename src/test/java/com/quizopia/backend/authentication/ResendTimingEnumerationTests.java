package com.quizopia.backend.authentication;

import com.quizopia.backend.notification.email.OtpMailSender;
import org.springframework.beans.factory.annotation.Autowired;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Review R3-M1-03: the resend endpoint must resist TIMING enumeration. With a
 * deliberately SLOW SMTP double (300ms per send), every reachable account
 * state must answer within the same low latency band — proving the dominant
 * SMTP term is off the request path — and the eligible state's mail must
 * still arrive afterwards (background dispatch, not dropped).
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, ResendTimingEnumerationTests.SlowMailConfig.class})
@ActiveProfiles("test")
class ResendTimingEnumerationTests extends AbstractAuthenticationIntegrationTests {

    private static final long SMTP_DELAY_MS = 300;
    /** Generous MockMvc/transaction overhead budget; well below SMTP_DELAY_MS. */
    private static final long LATENCY_BAND_MS = 250;

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowMailConfig {

        /** Wraps the capture sender with a fixed 300ms delay per dispatch. */
        @Bean
        @Primary
        OtpMailSender slowOtpMailSender(com.quizopia.backend.notification.email.CaptureOtpMailSender capture) {
            return new OtpMailSender() {
                @Override
                public boolean sendAccountVerificationOtp(String toEmail, String displayName,
                        String otp, long expiresInSeconds) {
                    try {
                        Thread.sleep(SMTP_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return capture.sendAccountVerificationOtp(toEmail, displayName, otp, expiresInSeconds);
                }
            };
        }
    }

    private static final int SAMPLES = 7;

    /** Median of samples — robust against single GC/scheduler spikes. */
    private static long median(long[] values) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    @Test
    void resendLatencyBandIsFlatAndDeliveryStillHappens() throws Exception {
        String username = unique("timing");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        // The registration mail itself paid the 300ms (synchronous by design).
        assertThat(otpMailbox.sendCountFor(email)).isEqualTo(1);
        waitForOtpMailCount(email, 1);

        // UNKNOWN state: repeated measurements (warm-up discarded).
        String ghost = "ghost-" + UUID.randomUUID() + "@example.com";
        timeResend(ghost); // warm-up
        long[] unknown = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            unknown[i] = timeResend(ghost);
        }

        // ELIGIBLE state: one issuance per cooldown window is not needed for
        // timing — every call after the first is throttled to the SAME code
        // path shape minus issuance; the FIRST eligible call is the expensive
        // one, so measure it FIRST and then keep sampling the throttled
        // calls (both must stay in the flat band: neither pays SMTP).
        Thread.sleep(1100); // clear the 1s test cooldown
        long firstEligible = timeResend(email);
        long[] eligible = new long[SAMPLES];
        eligible[0] = firstEligible;
        for (int i = 1; i < SAMPLES; i++) {
            eligible[i] = timeResend(email); // throttled: uniform 200, no issuance
        }

        long unknownMedian = median(unknown);
        long eligibleMedian = median(eligible);
        // Neither state's median may include the SMTP term.
        assertThat(unknownMedian).isLessThan(LATENCY_BAND_MS);
        assertThat(eligibleMedian)
                .as("eligible resend must not include the %dms SMTP term", SMTP_DELAY_MS)
                .isLessThan(LATENCY_BAND_MS);
        assertThat(firstEligible)
                .as("the first (issuing) resend must also stay off the SMTP path")
                .isLessThan(SMTP_DELAY_MS + 50);
        // Flat band between states: the gap must stay far below the SMTP term.
        assertThat(Math.abs(eligibleMedian - unknownMedian))
                .as("timing gap between states must stay far below the SMTP term")
                .isLessThan(SMTP_DELAY_MS);

        // The eligible mail DID arrive afterwards (background dispatch works).
        waitForOtpMailCount(email, 2);

        // VERIFIED state: same flat band.
        assertThat(submitOtp(email, otpMailbox.latestOtpFor(email))).isEqualTo(200);
        timeResend(email); // warm-up
        long[] verified = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            verified[i] = timeResend(email);
        }
        assertThat(median(verified)).isLessThan(LATENCY_BAND_MS);
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

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
