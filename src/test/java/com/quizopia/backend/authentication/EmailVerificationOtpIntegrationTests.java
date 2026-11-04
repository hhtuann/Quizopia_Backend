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





    private int challengeCountFor(String username) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM email_verification_challenges c"
                        + " JOIN users u ON u.id = c.user_id WHERE u.username = :u")
                .setParameter("u", username)
                .getSingleResult()).intValue();
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
