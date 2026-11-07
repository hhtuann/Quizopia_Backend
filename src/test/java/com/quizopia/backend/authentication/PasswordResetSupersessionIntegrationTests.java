package com.quizopia.backend.authentication;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F3 regression (independent reset security review): a pending reset token
 * must NOT survive a password change — the shared credential-mutation event
 * supersedes it in the same transaction.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
class PasswordResetSupersessionIntegrationTests extends AbstractAuthenticationIntegrationTests {
    private static final String OLD_PASSWORD = "Passw0rd!";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.quizopia.backend.testsupport.MutableClock clock;
    @Autowired private com.quizopia.backend.notification.email.CapturePasswordResetMailSender resetMailbox;

    private String username;
    private String email;
    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now());
        username = "supers" + System.nanoTime();
        email = username + "@example.test";
        registerAndVerify(username, email, OLD_PASSWORD);
        accessToken = extractAccessToken(login(username, OLD_PASSWORD));
    }

    private String requestToken() throws Exception {
        int status = mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(202);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && resetMailbox.sendCountFor(email) == 0) {
            Thread.sleep(50);
        }
        String url = resetMailbox.latestUrlFor(email);
        assertThat(url).isNotNull();
        return url.substring(url.indexOf("#token=") + 7);
    }

    private int resetStatus(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"OtherPassw0rd!\""
                                + ",\"confirmPassword\":\"OtherPassw0rd!\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void passwordChangeSupersedesPendingResetToken() throws Exception {
        String token = requestToken();
        int changed = mockMvc.perform(post("/api/account/security/change-password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + OLD_PASSWORD + "\",\"newPassword\":\"Changed0ne!\""
                                + ",\"confirmPassword\":\"Changed0ne!\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(changed).isEqualTo(204);
        String status = jdbc.queryForObject(
                "SELECT c.status FROM password_reset_challenges c JOIN users u ON u.id = c.user_id"
                        + " WHERE u.username = ?", String.class, username);
        assertThat(status).isEqualTo("SUPERSEDED");
        assertThat(resetStatus(token)).isEqualTo(400);
    }


}
