package com.quizopia.backend.community;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operator runbook is an ACCEPTANCE-TESTED ARTIFACT (ADR-006 §5,
 * issue #42 "operator setup tested"): this test parses
 * {@code docs/runbooks/community-moderator-provisioning.md}, executes
 * its ```sql blocks VERBATIM (placeholder substitution only) against
 * the Testcontainers PostgreSQL, and proves the documented outcome —
 * grant → the moderator can act (and MODERATOR_GRANTED is audited with
 * the OPERATOR account as actor); revoke → immediate 403 (and
 * MODERATOR_REVOKED audited); the bounded-expiry variant SQL is valid
 * and re-grants; verification queries run clean.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunityModeratorRunbookIntegrationTests {

    private static final Path RUNBOOK =
            Path.of("docs/runbooks/community-moderator-provisioning.md");
    private static final Pattern SQL_BLOCK = Pattern.compile("```sql\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern STEP = Pattern.compile("--\\s*runbook-step:\\s*(\\S+)");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    private long moderatorId;
    private long publicationId;
    private Map<String, String> steps;

    @BeforeEach
    void setUp() throws IOException {
        assertThat(Files.exists(RUNBOOK))
                .as("runbook must exist at %s (module working directory)", RUNBOOK.toAbsolutePath())
                .isTrue();
        steps = parseRunbookSteps();

        moderatorId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('rbm%s', 'rbm%s@t.example.com', 'h', 'RB', 'ACTIVE', now(), 0, 0, now())
                """.formatted(seq, seq));
        long teacherId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('rbt%s', 'rbt%s@t.example.com', 'h', 'RB', 'ACTIVE', now(), 0, 0, now())
                """.formatted(seq, seq));
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'STUDENT'",
                moderatorId);
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                teacherId);
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'RBE%s', 'Runbook exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, seq));
        publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Runbook publication', 'RB-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
    }

    @Test
    void runbookSqlGrantsModeratesRevokesAndAuditsExactlyAsDocumented() throws Exception {
        String operatorUsername = "rbop" + seq;

        // ---- Step 1: one-time operator account (verbatim runbook SQL). ----
        executeStep("operator-account", Map.of(
                ":operator_username", operatorUsername,
                ":operator_email", operatorUsername + "@operators.example.com",
                ":operator_password_hash", "test-only-hash"));
        executeStep("resolve-operator", Map.of(":operator_username", operatorUsername));
        long operatorId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, operatorUsername);
        assertThat(operatorId).isPositive();

        // The operator account is role-less (runbook precondition).
        assertThat(jdbc.queryForList("""
                        SELECT r.code FROM user_roles ur
                        JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?
                        """, String.class, operatorId))
                .isEmpty();

        // ---- Preconditions + grant (verbatim). ----
        var base = Map.of(
                ":operator_user_id", String.valueOf(operatorId),
                ":moderator_user_id", String.valueOf(moderatorId));
        executeStep("preconditions", base);
        executeStep("grant", with(base, ":grant_reason", "Operator provisioning 2026-11-08"));

        // The documented functional outcome: moderator gate opens.
        mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(moderatorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true));
        // …and the moderator can act.
        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/hide")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"runbook-granted moderator hid this\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationState").value("HIDDEN"));
        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/restore")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"runbook restore\"}"))
                .andExpect(status().isOk());

        // MODERATOR_GRANTED audit row with the OPERATOR as actor.
        assertThat(jdbc.queryForMap("""
                        SELECT a.action, a.actor_user_id, a.publication_id, a.reason
                        FROM community_moderation_actions a
                        WHERE a.action = 'MODERATOR_GRANTED' AND a.actor_user_id = ?
                        """, operatorId))
                .containsEntry("action", "MODERATOR_GRANTED")
                .containsEntry("actor_user_id", operatorId)
                .containsEntry("reason", "Operator provisioning 2026-11-08");

        executeStep("verify-grant", base); // documented verification queries run clean

        // ---- Revoke (verbatim) → immediate 403 on the next request. ----
        executeStep("revoke", with(base, ":revoke_reason", "Operator revocation 2026-11-08"));

        mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(moderatorId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/hide")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too late\"}"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForMap("""
                        SELECT a.action, a.actor_user_id
                        FROM community_moderation_actions a
                        WHERE a.action = 'MODERATOR_REVOKED' AND a.actor_user_id = ?
                        """, operatorId))
                .containsEntry("action", "MODERATOR_REVOKED")
                .containsEntry("actor_user_id", operatorId);

        executeStep("verify-revoke", base);

        // ---- Bounded-expiry variant (verbatim) re-grants with expires_at. ----
        executeStep("grant-with-expiry", with(base, ":expires_at", "now() + interval '30 days'"));
        mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(moderatorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true));
        assertThat(jdbc.queryForObject("""
                        SELECT ur.expires_at IS NOT NULL FROM user_roles ur
                        JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = ? AND r.code = 'COMMUNITY_MODERATOR'
                        """, Boolean.class, moderatorId))
                .isTrue();

        // An EXPIRED grant denies immediately (runbook §3b semantics).
        jdbc.update("""
                UPDATE user_roles
                SET assigned_at = now() - interval '2 seconds',
                    expires_at = now() - interval '1 second'
                WHERE user_id = ? AND role_id = (SELECT id FROM roles WHERE code = 'COMMUNITY_MODERATOR')
                """, moderatorId);
        mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(moderatorId)))
                .andExpect(status().isForbidden());

        // Rollback hygiene: the documented revoke cleans up either form.
        executeStep("revoke", with(base, ":revoke_reason", "Cleanup after expiry variant"));
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM user_roles ur
                        JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = ? AND r.code = 'COMMUNITY_MODERATOR'
                        """, Integer.class, moderatorId))
                .isZero();
    }

    // ==================================================================
    // runbook parsing + verbatim execution
    // ==================================================================

    /** ```sql blocks keyed by their `-- runbook-step:` marker, in document order. */
    private static Map<String, String> parseRunbookSteps() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher blocks = SQL_BLOCK.matcher(Files.readString(RUNBOOK));
        while (blocks.find()) {
            String body = blocks.group(1);
            Matcher step = STEP.matcher(body);
            assertThat(step.find())
                    .as("every runbook sql block carries a runbook-step marker")
                    .isTrue();
            out.put(step.group(1), body);
        }
        assertThat(out.keySet()).as("all documented steps are present").contains(
                "operator-account", "resolve-operator", "preconditions", "grant",
                "verify-grant", "revoke", "verify-revoke", "grant-with-expiry");
        return out;
    }

    /**
     * Executes one runbook block VERBATIM: placeholder substitution only
     * (the documented psql -v equivalents), then statement-by-statement
     * on ';' at end-of-line. SELECT blocks simply run (their results
     * are asserted separately with direct queries).
     */
    private void executeStep(String step, Map<String, String> variables) {
        String sql = steps.get(step);
        assertThat(sql).as("runbook step %s exists", step).isNotNull();
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            sql = sql.replace(variable.getKey(), variable.getValue());
        }
        assertThat(sql)
                .as("step %s fully substituted (no :placeholder left)", step)
                .doesNotMatch(".*:[a-zA-Z_].*");

        StringBuilder statement = new StringBuilder();
        for (String line : sql.split("\n")) {
            statement.append(line).append('\n');
            if (line.trim().endsWith(";")) {
                jdbc.execute(statement.toString().trim());
                statement.setLength(0);
            }
        }
        assertThat(statement.toString().isBlank())
                .as("step %s ends with a terminated statement", step)
                .isTrue();
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> out = new LinkedHashMap<>(base);
        out.put(key, value);
        return out;
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
