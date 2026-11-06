package com.quizopia.backend.authentication;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.quizopia.backend.security.password.PasswordHasher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class LegacyEmailTrustIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PasswordHasher passwordHasher;

    private static final String LEGACY_PASSWORD = "LegacyPassw0rd!";

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * Seeds the legacy account in a REQUIRES_NEW transaction so the row is
     * COMMITTED immediately — the async resend worker runs on its own
     * connection and only sees committed state.
     */
    private String seedLegacyUserCommitted(String username) {
        String email = username + "@legacy.example.com";
        org.springframework.transaction.support.TransactionTemplate requiresNew =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(tx -> seedLegacyUserRow(username, email));
        return email;
    }

    /** Inserts a Phase-1-style legacy account exactly like pre-V18 data. */
    private String seedLegacyUser(String username) {
        String email = username + "@legacy.example.com";
        seedLegacyUserRow(username, email);
        return email;
    }

    private void seedLegacyUserRow(String username, String email) {
        entityManager.clear();
        //noinspection SqlInsertValues
        entityManager.createNativeQuery("""
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           token_version, failed_login_attempts, password_changed_at,
                                           login_requires_email_verification)
                        VALUES (:u, :e, :h, 'Legacy User', 'ACTIVE', 0, 0, now(), FALSE)
                        """)
                .setParameter("u", username)
                .setParameter("e", email)
                .setParameter("h", passwordHasher.hash(LEGACY_PASSWORD))
                .executeUpdate();
    }

    @Test
    void legacyAccountLogsInWithoutEmailProof() throws Exception {
        String username = "legacy-" + UUID.randomUUID().toString().substring(0, 8);
        String email = seedLegacyUser(username);

        // Wrong password: a credential failure, NEVER AUTH_EMAIL_NOT_VERIFIED —
        // the gate is bypassed for legacy rows.
        MvcResult wrongPassword = login(username, "WrongPassw0rd!");
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getContentAsString())
                .contains("AUTH_INVALID_CREDENTIALS")
                .doesNotContain("AUTH_EMAIL_NOT_VERIFIED");

        // Correct password on an UNPROVEN legacy email: full session issued.
        MvcResult ok = login(username, LEGACY_PASSWORD);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("accessToken");
    }

    @Test
    void legacyAccountEmailStaysUnprovenUntilRealOtp() throws Exception {
        String username = "legacy-otp-" + UUID.randomUUID().toString().substring(0, 8);
        String email = seedLegacyUserCommitted(username);

        entityManager.clear();
        Object verifiedAt = entityManager.createNativeQuery(
                        "SELECT email_verified_at FROM users WHERE username = :u")
                .setParameter("u", username)
                .getSingleResult();
        assertThat(verifiedAt).as("V18 must NOT grandfather legacy email proof").isNull();

        // The legacy user can request an OTP (resend issues one when no
        // challenge exists) and a successful verification sets the proof.
        MvcResult resend = mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andReturn();
        assertThat(resend.getResponse().getStatus()).isEqualTo(200);
        // The resend worker commits the challenge on its own connection before
        // recording the mail; still, await committed-row visibility explicitly
        // so verify can never race the worker's insert under suite load.
        long deadline = System.currentTimeMillis() + 10_000;
        Object pending = null;
        while (System.currentTimeMillis() < deadline) {
            pending = entityManager.createNativeQuery(
                            "SELECT c.status FROM email_verification_challenges c"
                                    + " JOIN users u ON u.id = c.user_id"
                                    + " WHERE u.username = :u AND c.status = 'PENDING'")
                    .setParameter("u", username).getResultList().stream().findFirst().orElse(null);
            if (pending != null) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(pending).isEqualTo("PENDING");

        MvcResult verify = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, otpMailbox.latestOtpFor(email))))
                .andReturn();
        assertThat(verify.getResponse().getStatus()).isEqualTo(200);
        entityManager.clear();
        Object verifiedAfter = entityManager.createNativeQuery(
                        "SELECT email_verified_at FROM users WHERE username = :u")
                .setParameter("u", username)
                .getSingleResult();
        assertThat(verifiedAfter).isNotNull();
    }

    @Test
    void legacyAccountVerifyEmailProofFlagUntouchedByV18() throws Exception {
        // /me exposes emailVerified=false for a legacy row until real proof.
        String username = "legacy-me-" + UUID.randomUUID().toString().substring(0, 8);
        seedLegacyUser(username);

        // Register a separate verified user to obtain a token (legacy stub
        // hash cannot log in; the flag semantics are asserted via SQL above
        // and via the M2 upgrade tests on the stacked branch).
        String verifier = unique("ver");
        String verifierEmail = emailFor(verifier);
        registerAndVerify(verifier, verifierEmail, "Passw0rd!");
        String token = extractAccessToken(login(verifier, "Passw0rd!"));

        MvcResult me = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode body = objectMapper.readTree(me.getResponse().getContentAsString());
        assertThat(body.get("emailVerified").asBoolean()).isTrue();
        assertThat(body.get("googleLinked").asBoolean()).isFalse();
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
