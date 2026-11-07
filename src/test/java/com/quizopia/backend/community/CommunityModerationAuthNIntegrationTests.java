package com.quizopia.backend.community;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthN-layer outcomes for the moderator routes (ADR-006 §7 auth
 * matrix, issue #42) using REAL access tokens through the JWT decoder +
 * {@code QuizopiaJwtAuthenticationConverter} (per-request live-DB
 * revalidation):
 *
 * <ul>
 *   <li><b>DISABLED moderator</b> — a token minted while ACTIVE is
 *       rejected 401 the moment the account is disabled, even though
 *       the moderator grant row is untouched.</li>
 *   <li><b>UNVERIFIED</b> — cannot log in at all, so no token for any
 *       authenticated route exists to test with (documented at the
 *       AuthN layer, §7; {@code CommunityAuthNIntegrationTests} covers
 *       the same rule for the owner routes).</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
class CommunityModerationAuthNIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void disabledModeratorTokenIsRejected401AtAuthNOnEveryModerationRoute() throws Exception {
        String username = "moddis" + System.nanoTime();
        String email = username + "@t.example.com";
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));

        // Operator-style provisioning: grant the dormant role directly.
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.id, r.id FROM users u, roles r
                WHERE u.username = ? AND r.code = 'COMMUNITY_MODERATOR'
                """, username);

        // Sanity: while ACTIVE the grant gate answers 200 granted:true.
        int whileActive = get("/api/community/moderation/grant", token);
        assertThat(whileActive).isEqualTo(200);

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE username = ?", username);

        assertThat(get("/api/community/moderation/grant", token)).isEqualTo(401);
        assertThat(get("/api/community/moderation/reports", token)).isEqualTo(401);
        assertThat(get("/api/community/moderation/publications/1/history", token)).isEqualTo(401);
    }

    private int get(String path, String token) throws Exception {
        return mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get(path)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }
}
