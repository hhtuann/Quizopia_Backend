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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** M5-C: profile edit cannot be smuggled through the credential route, and vice versa. */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
@Transactional
class ChangePasswordValidationIntegrationTests extends AbstractAuthenticationIntegrationTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.quizopia.backend.testsupport.MutableClock clock;

    private String token;
    private String username;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now());
        username = "chpwv" + System.nanoTime();
        registerAndVerify(username, username + "@example.test", "Passw0rd!");
        token = extractAccessToken(login(username, "Passw0rd!"));
    }

    @Test
    void successfulChangePreservesEveryProtectedFieldAndRevokesAcrossPaths() throws Exception {
        MvcResult before = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andReturn();
        String emailBefore = objectMapper.readTree(before.getResponse().getContentAsString()).get("email").asString();

        MvcResult change = mockMvc.perform(post("/api/account/security/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Passw0rd!","newPassword":"NewPassw0rd!","confirmPassword":"NewPassw0rd!"}
                                """))
                .andReturn();
        assertThat(change.getResponse().getStatus()).isEqualTo(204);

        var row = jdbc.queryForMap(
                "SELECT username, email, display_name, phone_encrypted, status, email_verified_at, token_version"
                        + " FROM users WHERE username = ?", username);
        assertThat(row.get("username")).isEqualTo(username);
        assertThat(row.get("email")).isEqualTo(emailBefore);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("email_verified_at")).isNotNull();
        assertThat((Integer) row.get("token_version")).isEqualTo(1);
        assertThat(row.get("phone_encrypted")).isNotNull();

        // /api/auth/me with the revoked JWT is rejected immediately.
        assertThat(mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        // Profile route also rejects the revoked JWT.
        assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/account/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Should Not Apply","phone":null}
                                """))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT display_name FROM users WHERE username = ?", String.class, username))
                .isNotEqualTo("Should Not Apply");
    }

    @Test
    void unknownBodyFieldsAreRejectedBeforeAnyCredentialWork() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/account/security/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Passw0rd!","newPassword":"NewPassw0rd!","confirmPassword":"NewPassw0rd!","tokenVersion":99}
                                """))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT token_version FROM users WHERE username = ?", Integer.class, username))
                .isZero();
    }

    @Test
    void nullFieldsAndBlankPasswordsAreRejected() throws Exception {
        for (String body : new String[]{
                "{\"currentPassword\":null,\"newPassword\":\"NewPassw0rd!\",\"confirmPassword\":\"NewPassw0rd!\"}",
                "{\"currentPassword\":\"Passw0rd!\",\"newPassword\":\" \",\"confirmPassword\":\" \"}",
                "{\"newPassword\":\"NewPassw0rd!\",\"confirmPassword\":\"NewPassw0rd!\"}"
        }) {
            MvcResult result = mockMvc.perform(post("/api/account/security/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE username = ?",
                Integer.class, username)).isZero();
    }
}
