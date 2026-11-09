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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AuthN-layer outcomes for the #66 comment routes (ADR-007 §5 auth
 * matrix) using REAL access tokens through the actual JWT decoder +
 * {@code QuizopiaJwtAuthenticationConverter} (per-request live-DB
 * revalidation): a token minted for a then-active STUDENT is rejected
 * with 401 on every comment route after the account is disabled, while
 * the routes are reachable while ACTIVE; guests are 401 on the
 * authenticated routes but 200 on the PUBLIC comments page; and a
 * role-less authenticated caller is 403 COMMUNITY_ACCESS_DENIED
 * (service-layer permission gate).
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
class CommunityCommentAuthNIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    @Test
    void disabledUserTokenIsRejected401AtAuthNOnEveryCommentRoute() throws Exception {
        String username = "comdis" + System.nanoTime();
        String email = username + "@t.example.com";
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));
        long studentId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);

        long teacherId = insertUser("comdt");
        jdbc.update("INSERT INTO user_roles (user_id, role_id)"
                + " SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherId);
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'COMX%s', 'Comment AuthN exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, seq++));
        long publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Comment AuthN publication', 'Com-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
        long commentId = insert("""
                INSERT INTO community_comments (publication_id, author_user_id, author_alias, body)
                VALUES (%d, %d, 'AuthN-Alias', 'authn probe')
                """.formatted(publicationId, studentId));

        // Sanity: while ACTIVE the routes are reachable (2xx, not 401/403).
        int whileActiveCreate = mockMvc.perform(post("/api/community/publications/"
                        + publicationId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"reachable\",\"alias\":\"Live-Alias\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(whileActiveCreate).isEqualTo(201);

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE username = ?", username);

        int createStatus = mockMvc.perform(post("/api/community/publications/"
                        + publicationId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"blocked\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(createStatus).as("disabled account: comment create is unreachable").isEqualTo(401);

        int patchStatus = mockMvc.perform(patch("/api/community/comments/" + commentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"blocked\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(patchStatus).isEqualTo(401);

        int deleteStatus = mockMvc.perform(delete("/api/community/comments/" + commentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(deleteStatus).isEqualTo(401);

        int reportStatus = mockMvc.perform(post("/api/community/comments/" + commentId + "/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"SPAM\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(reportStatus).isEqualTo(401);

        for (String route : new String[]{"/api/community/moderation/comment-reports",
                "/api/community/moderation/comments/" + commentId + "/history"}) {
            int moderationStatus = mockMvc.perform(get(route)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn().getResponse().getStatus();
            assertThat(moderationStatus).isEqualTo(401);
        }

        // The PUBLIC comments page stays anonymous-readable for everyone.
        int publicStatus = mockMvc.perform(get("/api/public/community/publications/"
                        + publicationId + "/comments"))
                .andReturn().getResponse().getStatus();
        assertThat(publicStatus).isEqualTo(200);
    }

    @Test
    void guestsGet401EverywhereExceptThePublicCommentsPageAndRolelessGet403() throws Exception {
        String username = "comrole" + System.nanoTime();
        registerAndVerify(username, username + "@t.example.com", "Passw0rd!");
        // Strip the registration-seeded STUDENT role: authenticated, role-less.
        long roleless = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", roleless);

        long teacherId = insertUser("comrt");
        jdbc.update("INSERT INTO user_roles (user_id, role_id)"
                + " SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherId);
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'COMR%s', 'Roleless exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, seq++));
        long publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Roleless publication', 'Rol-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
        long commentId = insert("""
                INSERT INTO community_comments (publication_id, author_user_id, author_alias, body)
                VALUES (%d, %d, 'Rol-Alias', 'roleless probe')
                """.formatted(publicationId, teacherId));

        // Guests: authenticated-surface routes → 401.
        assertThat(mockMvc.perform(post("/api/community/publications/" + publicationId
                        + "/comments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"guest\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(patch("/api/community/comments/" + commentId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"guest\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(delete("/api/community/comments/" + commentId))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/community/moderation/comment-reports"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        // Guests: the public comments page → 200.
        assertThat(mockMvc.perform(get("/api/public/community/publications/"
                        + publicationId + "/comments"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        // Role-less authenticated caller: 403 COMMUNITY_ACCESS_DENIED
        // (no COMMUNITY_COMMENT / COMMUNITY_REPORT permission).
        assertThat(mockMvc.perform(post("/api/community/publications/" + publicationId
                        + "/comments").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j
                        .subject(String.valueOf(roleless))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"no role\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(post("/api/community/comments/" + commentId + "/reports")
                        .with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j
                        .subject(String.valueOf(roleless))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"SPAM\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private long insertUser(String prefix) {
        return insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'CA', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    // Non-@Transactional suite: committed fixtures would leak into the
    // shared container and break the exact-count catalog suites (which
    // run earlier in the default run order). Only the rows this class
    // created (created_at >= class load) are removed; publication
    // children CASCADE along.
    private static final java.time.Instant CLASS_START = java.time.Instant.now();

    @org.junit.jupiter.api.AfterEach
    void cleanCommittedFixtures() {
        jdbc.update("DELETE FROM community_publications WHERE created_at >= CAST(? AS timestamptz)", CLASS_START);
        jdbc.update("DELETE FROM community_member_profiles WHERE created_at >= CAST(? AS timestamptz)", CLASS_START);
    }

}
