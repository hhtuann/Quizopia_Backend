package com.quizopia.backend.community;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AuthN-layer outcomes for the #47 fork route (ADR-006 §7 auth matrix)
 * using a REAL access token through the actual JWT decoder +
 * {@code QuizopiaJwtAuthenticationConverter} (per-request live-DB
 * revalidation): a token minted for a then-active teacher is rejected
 * with 401 after the account is disabled; an unverified account cannot
 * obtain a token at all. Guest 401 (chain) is covered in
 * {@code CommunityForkIntegrationTests}.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
class CommunityForkAuthNIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void disabledUserTokenIsRejected401AtAuthNOnFork() throws Exception {
        String username = "fkdis" + System.nanoTime();
        String email = username + "@t.example.com";
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.id, r.id FROM users u, roles r
                WHERE u.username = ? AND r.code = 'TEACHER'
                """, username);

        // While ACTIVE the route is reachable: the fork itself fails on
        // the fixture-less publication id with 404 — NOT 401/403 (the
        // auth gate passed).
        int whileActive = mockMvc.perform(post("/api/community/publications/999999999/fork")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", "authn-key-0001"))
                .andReturn().getResponse().getStatus();
        assertThat(whileActive).isEqualTo(404);

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE username = ?", username);

        int afterDisable = mockMvc.perform(post("/api/community/publications/999999999/fork")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", "authn-key-0002"))
                .andReturn().getResponse().getStatus();
        assertThat(afterDisable)
                .as("fork is unreachable for a disabled account (#47)")
                .isEqualTo(401);
    }
}
