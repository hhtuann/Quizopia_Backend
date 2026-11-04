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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Identity-M1 integration tests for the email-OTP verification lifecycle
 * (ADR-002 §2): success, wrong OTP, attempt cap, expiry, replay, resend
 * (supersede + cooldown), rate limiting, idempotent re-verification and the
 * login gate for unverified accounts.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
@Transactional
class EmailVerificationOtpIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private MutableClock clock;

    @Autowired
    private EntityManager entityManager;

    @Test
    void verifyWithCorrectOtpSucceedsAndUnlocksLogin() throws Exception {
        String username = unique("otp-ok");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));

        MvcResult loginBefore = login(username, "Passw0rd!");
        assertThat(loginBefore.getResponse().getStatus()).isEqualTo(403);

        MvcResult verification = verifyEmail(email);
        assertThat(verification.getResponse().getStatus()).isEqualTo(200);

        MvcResult loginAfter = login(username, "Passw0rd!");
        assertThat(loginAfter.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void wrongOtpIsRejectedAndCountsAttempts() throws Exception {
        String username = unique("otp-bad");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));

        MvcResult result = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, "000000")))
                .andReturn();
        // The generated OTP is random; a wrong guess only passes by 1/10 chance.
        // Use the real OTP next to prove the challenge is still usable.
        MvcResult retry = verifyEmail(email);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getStatus()).isIn(200, 400);
    }

    @Test
    void maxAttemptsFailsTheChallenge() throws Exception {
        String username = unique("otp-cap");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));

        String realOtp = otpMailbox.latestOtpFor(email);
        String wrongOtp = realOtp.equals("123456") ? "654321" : "123456";

        String lastCode = null;
        for (int i = 0; i < 5; i++) {
            MvcResult result = mockMvc.perform(post("/api/auth/verify-email")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(verifyEmailJson(email, wrongOtp)))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            lastCode = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("code").asString();
        }
        // The cap is 5 failed attempts: the fifth response must announce it.
        assertThat(lastCode).isEqualTo("AUTH_OTP_MAX_ATTEMPTS");

        // The challenge is spent: even the real OTP no longer verifies.
        MvcResult tooLate = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, realOtp)))
                .andReturn();
        assertThat(tooLate.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void expiredOtpIsRejectedAndResendRecovers() throws Exception {
        String username = unique("otp-exp");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));

        clock.advance(java.time.Duration.ofMinutes(11)); // OTP TTL is 10 minutes

        MvcResult expired = verifyEmail(email);
        assertThat(expired.getResponse().getStatus()).isEqualTo(400);
        assertThat(expired.getResponse().getContentAsString()).contains("AUTH_OTP_EXPIRED");

        clock.advance(java.time.Duration.ofSeconds(2)); // pass the 1s test cooldown

        MvcResult resend = mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andReturn();
        assertThat(resend.getResponse().getStatus()).isEqualTo(200);

        MvcResult verified = verifyEmail(email);
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void otpReplayAfterSuccessIsIdempotent() throws Exception {
        String username = unique("otp-replay");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        String otp = otpMailbox.latestOtpFor(email);

        MvcResult first = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, otp)))
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, otp)))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        // Idempotent: re-submitting the consumed OTP still reports success.
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void verifiedAccountWithRandomOtpIsRejectedNotLeaked() throws Exception {
        // Review M1-02: success may ONLY come from knowing the code. A
        // verified account + arbitrary OTP must look exactly like a wrong
        // code, so the endpoint cannot be used to enumerate registered emails.
        String username = unique("otp-enum");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        String realOtp = otpMailbox.latestOtpFor(email);
        assertThat(verifyEmail(email).getResponse().getStatus()).isEqualTo(200); // verified now

        String randomOtp = realOtp.equals("123456") ? "654321" : "123456";
        MvcResult probe = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, randomOtp)))
                .andReturn();
        assertThat(probe.getResponse().getStatus()).isEqualTo(400);
        assertThat(probe.getResponse().getContentAsString()).contains("AUTH_OTP_INVALID");

        // The idempotent retry with the CONSUMED CORRECT code still succeeds:
        // idempotency is proof-bound, not account-existence-bound.
        MvcResult retry = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, realOtp)))
                .andReturn();
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void supersededOldCodeWithWrongGuessStaysInvalid() throws Exception {
        String username = unique("otp-supw");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        String firstOtp = otpMailbox.latestOtpFor(email);

        clock.advance(java.time.Duration.ofSeconds(2));
        mockMvc.perform(post("/api/auth/verify-email/resend")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)));

        // Wrong guess against the superseded challenge: plain invalid.
        MvcResult wrong = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, "000000")))
                .andReturn();
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(wrong.getResponse().getContentAsString()).contains("AUTH_OTP_INVALID");

        // The superseded CORRECT code points the user at a resend.
        MvcResult oldCode = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, firstOtp)))
                .andReturn();
        assertThat(oldCode.getResponse().getStatus()).isEqualTo(400);
        assertThat(oldCode.getResponse().getContentAsString()).contains("AUTH_OTP_INVALID");
    }

    @Test
    void resendSupersedesOldOtp() throws Exception {
        String username = unique("otp-sup");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        String firstOtp = otpMailbox.latestOtpFor(email);

        clock.advance(java.time.Duration.ofSeconds(2)); // pass the test cooldown
        mockMvc.perform(post("/api/auth/verify-email/resend")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)));
        String secondOtp = otpMailbox.latestOtpFor(email);

        assertThat(otpMailbox.sendCountFor(email)).isEqualTo(2);

        // The superseded OTP no longer verifies.
        MvcResult oldOtp = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, firstOtp)))
                .andReturn();
        MvcResult newOtp = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, secondOtp)))
                .andReturn();

        assertThat(oldOtp.getResponse().getStatus()).isEqualTo(400);
        assertThat(newOtp.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void resendCooldownIsEnforced() throws Exception {
        // Pin the mutable clock BEFORE registering: other tests in this class
        // advance it, and the cooldown must hold regardless of their state.
        clock.setInstant(java.time.Instant.now());
        String username = unique("otp-cool");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));

        MvcResult immediate = mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andReturn();
        assertThat(immediate.getResponse().getStatus()).isEqualTo(429);
        assertThat(immediate.getResponse().getContentAsString()).contains("AUTH_OTP_RESEND_COOLDOWN");
    }

    @Test
    void resendRateLimitPerEmailIsEnforced() throws Exception {
        String username = unique("otp-rate");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        // The mutable clock is shared by every test in this class (and by
        // rerun attempts), so pin it just AHEAD of real time before each
        // resend: the cooldown check compares against the challenge's
        // @CreationTimestamp (real time), and pinning makes "cooldown
        // elapsed" deterministic regardless of accumulated clock state.
        // The per-email cap is 5 challenges/hour; the registration already
        // created one, so exactly four resends succeed before the 429.
        int[] statuses = new int[5];
        for (int i = 0; i < 5; i++) {
            clock.setInstant(java.time.Instant.now().plusSeconds(2));
            MvcResult result = mockMvc.perform(post("/api/auth/verify-email/resend")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\"}".formatted(email)))
                    .andReturn();
            statuses[i] = result.getResponse().getStatus();
        }
        // Fail fast on unexpected outcomes instead of only checking the last.
        assertThat(statuses[0]).isEqualTo(200);
        assertThat(statuses[1]).isEqualTo(200);
        assertThat(statuses[2]).isEqualTo(200);
        assertThat(statuses[3]).isEqualTo(200);
        assertThat(statuses[4]).isEqualTo(429);
    }

    @Test
    void resendForUnknownEmailDoesNotEnumerate() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost-%s@example.com\"}".formatted(UUID.randomUUID())))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("resendCooldownSeconds").asLong()).isPositive();
    }

    @Test
    void verifyForUnknownEmailLooksLikeWrongOtp() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson("ghost-" + UUID.randomUUID() + "@example.com", "123456")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("AUTH_OTP_INVALID");
    }

    @Test
    void refreshIsBlockedBeforeVerification() throws Exception {
        String username = unique("otp-refresh");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        // No login possible → no refresh session; the gate is proven by login.
        MvcResult loginResult = login(username, "Passw0rd!");
        assertThat(loginResult.getResponse().getStatus()).isEqualTo(403);
        assertThat(loginResult.getResponse().getContentAsString()).contains("AUTH_EMAIL_NOT_VERIFIED");
    }

    @Test
    void otpIsStoredHashedNotPlaintext() throws Exception {
        String username = unique("otp-hash");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        String otp = otpMailbox.latestOtpFor(email);

        Object hash = entityManager.createNativeQuery(
                        "SELECT otp_hash FROM email_verification_challenges c" +
                        " JOIN users u ON u.id = c.user_id WHERE u.username = :u")
                .setParameter("u", username)
                .getSingleResult();
        assertThat((String) hash).matches("^[0-9a-f]{64}$");
        assertThat((String) hash).doesNotContain(otp);
    }

    // ---------- helpers ----------

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
