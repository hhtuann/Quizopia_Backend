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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
@Transactional
class ChangePasswordIntegrationTests extends AbstractAuthenticationIntegrationTests {
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired private com.quizopia.backend.testsupport.MutableClock clock;

    private String token;
    private String username;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now());
        username = "chpw" + System.nanoTime();
        registerAndVerify(username, username + "@example.test", "Passw0rd!");
        token = extractAccessToken(login(username, "Passw0rd!"));
    }

    private MvcResult change(String currentPassword, String newPassword, String confirmPassword,
                             String accessToken) throws Exception {
        return mockMvc.perform(post("/api/account/security/change-password")
                        .header("Authorization", "Bearer " + (accessToken == null ? "" : accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"%s","confirmPassword":"%s"}
                                """.formatted(currentPassword, newPassword, confirmPassword)))
                .andReturn();
    }

    @Test
    void correctChangeRevokesEverySessionAndRequiresRelogin() throws Exception {
        MvcResult secondLogin = login(username, "Passw0rd!");
        String secondRefresh = extractRefreshCookieValue(secondLogin);

        MvcResult result = change("Passw0rd!", "NewPassw0rd!", "NewPassw0rd!", token);
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");

        // Old access JWT is immediately unusable.
        assertThat(login(username, "NewPassw0rd!").getResponse().getStatus()).isEqualTo(200);
        assertThat(login(username, "Passw0rd!").getResponse().getStatus()).isIn(401, 423);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_sessions rs JOIN users u ON u.id = rs.user_id"
                        + " WHERE u.username = ? AND rs.revoked_at IS NULL",
                Integer.class, username)).isZero();
        assertThat(mockMvc.perform(refreshRequest(secondRefresh)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(change("NewPassw0rd!", "NewPassw0rd2!", "NewPassw0rd2!", token).getResponse().getStatus()).isEqualTo(401);
    }

    private String errorCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asString();
    }

    @Test
    void wrongCurrentPasswordCommitsLockoutAndReturnsInvalidCredentials() throws Exception {
        for (int i = 0; i < 4; i++) {
            MvcResult wrong = change("WrongPass1!", "NewPassw0rd!", "NewPassw0rd!", token);
            assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult fifth = change("WrongPass1!", "NewPassw0rd!", "NewPassw0rd!", token);
        // Dirty-check flush so the same-transaction JDBC read sees attempt #5.
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                "SELECT failed_login_attempts FROM users WHERE username = ?", Integer.class, username)).isEqualTo(5);
        // Sharing with login: the locked account cannot authenticate either.
        assertThat(errorCode(login(username, "Passw0rd!"))).isEqualTo("AUTH_ACCOUNT_LOCKED");
        assertThat(errorCode(change("Passw0rd!", "NewPassw0rd!", "NewPassw0rd!", token))).isEqualTo("AUTH_ACCOUNT_LOCKED");
    }

    @Test
    void rejectsWeakReusedMismatchedAndInvalidBodies() throws Exception {
        assertThat(change("Passw0rd!", "short", "short", token).getResponse().getStatus()).isEqualTo(400);
        assertThat(change("Passw0rd!", "NewPassw0rd!", "Different1!", token).getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(change("Passw0rd!", "Passw0rd!", "Passw0rd!", token))).isEqualTo("AUTH_VALIDATION_ERROR");
        MvcResult injected = mockMvc.perform(post("/api/account/security/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Passw0rd!","newPassword":"NewPassw0rd!","confirmPassword":"NewPassw0rd!","email":"attacker@example.test"}
                                """))
                .andReturn();
        assertThat(injected.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE username = ?", String.class, username))
                .isEqualTo(username + "@example.test");
    }

    @Test
    void unauthenticatedAndStaleEpochRequestsAreRejectedWithoutMutation() throws Exception {
        assertThat(mockMvc.perform(post("/api/account/security/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Passw0rd!","newPassword":"NewPassw0rd!","confirmPassword":"NewPassw0rd!"}
                                """))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        int version = jdbc.queryForObject("SELECT token_version FROM users WHERE username = ?", Integer.class, username);
        jdbc.update("UPDATE users SET token_version = ? WHERE username = ?", version + 1, username);
        assertThat(errorCode(change("Passw0rd!", "NewPassw0rd!", "NewPassw0rd!", token)))
                .isEqualTo("AUTH_ACCESS_TOKEN_INVALID");
        assertThat(login(username, "Passw0rd!").getResponse().getStatus()).isEqualTo(200);
    }

}
