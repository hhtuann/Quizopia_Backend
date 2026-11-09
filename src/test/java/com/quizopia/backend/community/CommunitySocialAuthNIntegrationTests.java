package com.quizopia.backend.community;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * AuthN-layer outcomes for the #65 social routes (ADR-007 §5 auth
 * matrix) using REAL access tokens through the actual JWT decoder +
 * {@code QuizopiaJwtAuthenticationConverter} (per-request live-DB
 * revalidation): a token minted for a then-active STUDENT is rejected
 * with 401 on every social route after the account is disabled, while
 * the routes are reachable while ACTIVE (the like PUT reaches 201 on a
 * seeded visible publication).
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
class CommunitySocialAuthNIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    @Test
    void disabledUserTokenIsRejected401AtAuthNOnEverySocialRoute() throws Exception {
        String username = "socdis" + System.nanoTime();
        String email = username + "@t.example.com";
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));
        long studentId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);

        // Registration seeds the STUDENT role; a visible publication to
        // act on (raw SQL — AuthN, not visibility, is under test).
        long teacherId = insertUser("socdt");
        jdbc.update("INSERT INTO user_roles (user_id, role_id)"
                + " SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherId);
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'SOCX%s', 'AuthN exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, seq++));
        long publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'AuthN publication', 'Soc-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));

        // Sanity: while ACTIVE the routes are reachable (2xx, not 401/403).
        int whileActiveLike = mockMvc.perform(put("/api/community/publications/"
                        + publicationId + "/like").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(whileActiveLike).isEqualTo(201);
        int whileActiveSummary = mockMvc.perform(get("/api/community/publications/"
                        + publicationId + "/social-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(whileActiveSummary).isEqualTo(200);

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE username = ?", username);

        for (String method : new String[]{"like", "rating"}) {
            int putStatus = mockMvc.perform(put("/api/community/publications/" + publicationId
                            + "/" + method)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rating\":3}"))
                    .andReturn().getResponse().getStatus();
            assertThat(putStatus)
                    .as("disabled account: PUT %s is unreachable (401 AuthN)", method)
                    .isEqualTo(401);

            int deleteStatus = mockMvc.perform(delete("/api/community/publications/"
                            + publicationId + "/" + method)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn().getResponse().getStatus();
            assertThat(deleteStatus)
                    .as("disabled account: DELETE %s is unreachable (401 AuthN)", method)
                    .isEqualTo(401);
        }

        int summaryStatus = mockMvc.perform(get("/api/community/publications/"
                        + publicationId + "/social-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(summaryStatus)
                .as("disabled account: authenticated social summary is unreachable (401 AuthN)")
                .isEqualTo(401);

        // The PUBLIC twin stays anonymous-readable for everyone —
        // disabled or not, no token is involved.
        int publicStatus = mockMvc.perform(get("/api/public/community/publications/"
                        + publicationId + "/social-summary"))
                .andReturn().getResponse().getStatus();
        assertThat(publicStatus).isEqualTo(200);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private long insertUser(String prefix) {
        return insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'SA', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
