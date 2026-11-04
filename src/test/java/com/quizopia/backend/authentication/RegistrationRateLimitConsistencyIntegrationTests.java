package com.quizopia.backend.authentication;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Review R3-M1-02: a rate-limit-denied registration must leave NO user row —
 * the OTP-issuance IP limit is checked BEFORE any persistence, so the denial
 * is exactly a non-event (retryable), not a half-committed account that
 * hijacks the email/username for later retries.
 */
@SpringBootTest(properties = "quizopia.email-verification.max-challenges-per-ip=2")
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class RegistrationRateLimitConsistencyIntegrationTests extends AbstractAuthenticationIntegrationTests {

    /** Dedicated context: the shared test profile raises the IP cap to keep
     *  unrelated suites healthy; THIS class pins the real cap (2) so the
     *  denial path is reached after exactly two issuances. */
    private static final int IP_CAP = 2;

    @Autowired
    private EntityManager entityManager;

    @Test
    void rateLimitDenialLeavesNoUserRow() throws Exception {
        // Exhaust the per-IP issuance window: IP_CAP registrations from the
        // MockMvc address (127.0.0.1), each consuming one challenge.
        // NOTE: the shared test context may already carry challenges from
        // other classes on this IP, so we simply register until a denial
        // occurs and then assert on THAT attempt.
        String deniedEmail = null;
        String deniedUsername = null;
        for (int i = 0; i < IP_CAP + 2; i++) {
            String username = unique("rl");
            MvcResult result = register(studentRegisterJson(
                    username, emailFor(username), "Passw0rd!", username + " Name", "+84991234567"));
            if (result.getResponse().getStatus() == 429) {
                deniedEmail = emailFor(username);
                deniedUsername = username;
                assertThat(result.getResponse().getContentAsString()).contains("AUTH_RATE_LIMITED");
                break;
            }
            assertThat(result.getResponse().getStatus())
                    .as("registration #%d should succeed until the IP cap", i)
                    .isEqualTo(201);
        }
        assertThat(deniedEmail).as("the IP cap must eventually deny a registration").isNotNull();

        entityManager.clear();
        // The denied registration is exactly a non-event.
        assertThat(((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM users WHERE username = :u OR email = :e")
                .setParameter("u", deniedUsername)
                .setParameter("e", deniedEmail)
                .getSingleResult()).intValue())
                .as("a rate-limit-denied registration must not persist any user row")
                .isZero();
        assertThat(((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM email_verification_challenges c"
                                + " JOIN users u ON u.id = c.user_id WHERE u.email = :e")
                .setParameter("e", deniedEmail)
                .getSingleResult()).intValue())
                .as("no challenge row for the denied registration either")
                .isZero();
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
