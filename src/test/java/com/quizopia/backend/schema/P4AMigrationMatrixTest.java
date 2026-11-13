package com.quizopia.backend.schema;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V33–V36 P4-A monitoring-foundation migration matrix (issues #80–#83),
 * following the staged-flyway house pattern ({@code V32CommunityCommentDisplayNameMigrationMatrixTest}):
 * a FRESH database inside the shared container migrates V1..V36, then plain
 * JDBC proves the schema contracts the P4-A services rely on:
 *
 * <ul>
 *   <li>{@code attempt_activity_events}: the event_type CHECK admits exactly
 *       the P4-A set (camera/AI/recording inserts are rejected), the source
 *       CHECK rejects unknown provenance, CLIENT rows require
 *       {@code client_occurred_at} + {@code client_sequence}, and
 *       {@code event_id} is UNIQUE (idempotency identity);</li>
 *   <li>{@code session_monitoring_policies}: 1:1 with {@code exam_sessions}
 *       (UNIQUE) and every boolean flag defaults to FALSE (monitoring is OFF
 *       unless explicitly enabled), retention defaults 30/90;</li>
 *   <li>{@code attempt_answer_history}: UNIQUE (attempt_id,
 *       attempt_question_id, accepted_sequence) — one accepted version per
 *       sequence, the P4-A3 atomicity witness;</li>
 *   <li>V36 permissions: ATTEMPT_ACTIVITY_INGEST / ATTEMPT_ACTIVITY_READ
 *       exist and are granted to exactly STUDENT + TEACHER (4 rows).</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class P4AMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    @Test
    void freshDatabaseEnforcesP4AMonitoringContracts() throws Exception {
        String dbName = createDatabase("p4amx");
        try {
            String url = urlFor(dbName);
            Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(
                    url, pg.getUsername(), pg.getPassword());
                 Statement st = c.createStatement()) {
                assertMigrationChainReachedV36(st);
                assertActivityEventConstraints(st);
                assertSessionMonitoringPolicyContract(st);
                assertAnswerHistoryUniqueness(st);
                assertMonitoringPermissions(st);
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // attempt_activity_events (V34)
    // ==================================================================

    private void assertActivityEventConstraints(Statement st) throws SQLException {
        Fixture f = scaffold(st);
        String uuid = "11111111-1111-1111-1111-111111111111";

        // Valid SERVER row (no client fields needed).
        insert(st, eventInsert(uuid, f.attemptId, "ATTEMPT_STARTED", "SERVER", "NULL", "NULL"));
        // The P4-A event-type allowlist rejects media/AI/recording types.
        for (String futureType : new String[]{"CAMERA_CONNECTED", "AI_ALERT_CREATED", "RECORDING_STARTED"}) {
            assertThatThrownBy(() -> execute(st, eventInsert(
                    UUID.randomUUID().toString(), f.attemptId, futureType, "SERVER", "NULL", "NULL")))
                    .as("event_type %s must be rejected by the P4-A CHECK allowlist", futureType)
                    .isInstanceOf(SQLException.class);
        }
        // Unknown provenance.
        assertThatThrownBy(() -> execute(st, eventInsert(
                UUID.randomUUID().toString(), f.attemptId, "TAB_HIDDEN", "WEIRD", "now()", "1")))
                .as("source CHECK admits only CLIENT and SERVER")
                .isInstanceOf(SQLException.class);
        // CLIENT rows require BOTH client_occurred_at and client_sequence.
        assertThatThrownBy(() -> execute(st, eventInsert(
                UUID.randomUUID().toString(), f.attemptId, "TAB_HIDDEN", "CLIENT", "NULL", "NULL")))
                .as("CLIENT row without client_occurred_at/client_sequence must be rejected")
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(st, eventInsert(
                UUID.randomUUID().toString(), f.attemptId, "TAB_HIDDEN", "CLIENT", "now()", "NULL")))
                .as("CLIENT row without client_sequence must be rejected")
                .isInstanceOf(SQLException.class);
        // A fully-populated CLIENT row is legal.
        insert(st, eventInsert(UUID.randomUUID().toString(), f.attemptId, "TAB_HIDDEN", "CLIENT", "now()", "7"));
        // event_id is the idempotency identity — a second row with the same UUID fails.
        assertThatThrownBy(() -> execute(st, eventInsert(
                uuid, f.attemptId, "ATTEMPT_SUBMITTED", "SERVER", "NULL", "NULL")))
                .as("UNIQUE (event_id) enforced")
                .isInstanceOf(SQLException.class);
        // metadata must be a JSON object (never an array/scalar).
        assertThatThrownBy(() -> execute(st, "INSERT INTO attempt_activity_events "
                + "(event_id, attempt_id, event_type, source, client_occurred_at, client_sequence, metadata) "
                + "VALUES ('" + UUID.randomUUID() + "'," + f.attemptId
                + ",'TAB_HIDDEN','CLIENT',now(),1,'[1,2]'::jsonb)"))
                .as("metadata CHECK requires a JSON object")
                .isInstanceOf(SQLException.class);
    }

    // ==================================================================
    // session_monitoring_policies (V33)
    // ==================================================================

    private void assertSessionMonitoringPolicyContract(Statement st) throws SQLException {
        Fixture f = scaffold(st);
        long policy = insert(st, "INSERT INTO session_monitoring_policies (exam_session_id) "
                + "VALUES (" + f.sessionId + ")");

        List<String> flags = rows(st, """
                SELECT monitoring_enabled || '|' || ai_detection_enabled || '|' || activity_tracking_enabled
                       || '|' || evidence_snapshots_enabled || '|' || full_recording_enabled
                       || '|' || evidence_clips_enabled
                       || '|' || media_retention_days || '|' || activity_retention_days
                FROM session_monitoring_policies WHERE id = %d
                """.formatted(policy));
        assertThat(flags).as("a bare row defaults to the all-OFF legacy policy (retention 30/90)")
                .containsExactly("false|false|false|false|false|false|30|90");

        assertThatThrownBy(() -> execute(st, "INSERT INTO session_monitoring_policies (exam_session_id) "
                + "VALUES (" + f.sessionId + ")"))
                .as("UNIQUE (exam_session_id) — strictly 1:1 with exam_sessions")
                .isInstanceOf(SQLException.class);
    }

    // ==================================================================
    // attempt_answer_history (V35)
    // ==================================================================

    private void assertAnswerHistoryUniqueness(Statement st) throws SQLException {
        Fixture f = scaffoldWithQuestion(st);
        insert(st, "INSERT INTO attempt_answer_history "
                + "(attempt_id, attempt_question_id, accepted_sequence, answer_payload, accepted_at) VALUES ("
                + f.attemptId + "," + f.attemptQuestionId + ",1,'{\"selectedOptionKey\":\"A\"}'::jsonb,now())");

        assertThatThrownBy(() -> execute(st, "INSERT INTO attempt_answer_history "
                + "(attempt_id, attempt_question_id, accepted_sequence, answer_payload, accepted_at) VALUES ("
                + f.attemptId + "," + f.attemptQuestionId + ",1,'{\"selectedOptionKey\":\"B\"}'::jsonb,now())"))
                .as("UNIQUE (attempt_id, attempt_question_id, accepted_sequence) — one accepted version per sequence")
                .isInstanceOf(SQLException.class);

        // Review MTX-1: the composite ownership FK must reject a
        // cross-attempt attempt_question_id at the DB level.
        Fixture other = scaffoldWithQuestion(st);
        assertThatThrownBy(() -> execute(st, "INSERT INTO attempt_answer_history "
                + "(attempt_id, attempt_question_id, accepted_sequence, answer_payload, accepted_at) VALUES ("
                + f.attemptId + "," + other.attemptQuestionId + ",1,'{}'::jsonb,now())"))
                .as("composite FK (attempt_id, attempt_question_id) rejects cross-attempt questions")
                .isInstanceOf(SQLException.class);

        // accepted_sequence >= 1 (the UPSERT's strictly-greater domain).
        assertThatThrownBy(() -> execute(st, "INSERT INTO attempt_answer_history "
                + "(attempt_id, attempt_question_id, accepted_sequence, answer_payload, accepted_at) VALUES ("
                + f.attemptId + "," + f.attemptQuestionId + ",0,null,now())"))
                .as("chk_aah_sequence rejects accepted_sequence < 1")
                .isInstanceOf(SQLException.class);

        // The next sequence of the same question is a legal second version.
        insert(st, "INSERT INTO attempt_answer_history "
                + "(attempt_id, attempt_question_id, accepted_sequence, answer_payload, accepted_at) VALUES ("
                + f.attemptId + "," + f.attemptQuestionId + ",2,'{\"selectedOptionKey\":\"C\"}'::jsonb,now())");
    }

    // ==================================================================
    // V36 permissions
    // ==================================================================

    private void assertMonitoringPermissions(Statement st) throws SQLException {
        assertThat(count(st, """
                SELECT count(*) FROM permissions
                WHERE code IN ('ATTEMPT_ACTIVITY_INGEST', 'ATTEMPT_ACTIVITY_READ')
                """))
                .as("both V36 permission codes exist")
                .isEqualTo(2);

        assertThat(count(st, """
                SELECT count(*) FROM role_permissions rp
                JOIN permissions p ON p.id = rp.permission_id
                JOIN roles r ON r.id = rp.role_id
                WHERE p.code IN ('ATTEMPT_ACTIVITY_INGEST', 'ATTEMPT_ACTIVITY_READ')
                """))
                .as("exactly 4 grants: 2 codes x (STUDENT, TEACHER)")
                .isEqualTo(4);

        assertThat(rows(st, """
                SELECT DISTINCT r.code FROM role_permissions rp
                JOIN permissions p ON p.id = rp.permission_id
                JOIN roles r ON r.id = rp.role_id
                WHERE p.code IN ('ATTEMPT_ACTIVITY_INGEST', 'ATTEMPT_ACTIVITY_READ')
                ORDER BY r.code
                """))
                .as("no role other than STUDENT and TEACHER holds the monitoring permissions")
                .containsExactly("STUDENT", "TEACHER");
    }

    private void assertMigrationChainReachedV36(Statement st) throws SQLException {
        assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                .as("migration chain reached V36")
                .containsExactly("36");
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private record Fixture(long sessionId, long attemptId, long attemptQuestionId) {}

    /** user -> PUBLISHED exam -> OPEN session -> attempt. */
    private Fixture scaffold(Statement st) throws SQLException {
        long nano = System.nanoTime();
        long user = insert(st, "INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('p4a" + nano + "','p4a" + nano + "@t.example.com','h','P4A')");
        long exam = insert(st, "INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points) "
                + "VALUES (" + user + ",'P4A" + nano + "','P4A exam','PUBLISHED',now(),1)");
        long session = insert(st, "INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, "
                + "max_attempts, duration_minutes, created_by, opened_at) VALUES ("
                + exam + ",'SS" + nano + "','t','OPEN',now()-interval '1 hour',now()+interval '2 hours',2,60,"
                + user + ",now()-interval '1 hour')");
        long attempt = insert(st, "INSERT INTO attempts (exam_session_id, user_id, attempt_number, started_at, deadline_at) "
                + "VALUES (" + session + "," + user + ",1,now(),now()+interval '1 hour')");
        return new Fixture(session, attempt, 0L);
    }

    /** scaffold() + one attempt_question (for the answer-history FK). */
    private Fixture scaffoldWithQuestion(Statement st) throws SQLException {
        long nano = System.nanoTime();
        long user = insert(st, "INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('p4aq" + nano + "','p4aq" + nano + "@t.example.com','h','P4AQ')");
        long exam = insert(st, "INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points) "
                + "VALUES (" + user + ",'P4AQ" + nano + "','P4AQ exam','PUBLISHED',now(),1)");
        long sec = insert(st, "INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long bank = insert(st, "INSERT INTO question_banks (created_by_user_id, code, name) "
                + "VALUES (" + user + ",'P4AQB" + nano + "','B')");
        long q = insert(st, "INSERT INTO questions (question_bank_id, code, created_by) "
                + "VALUES (" + bank + ",'P4AQ" + nano + "'," + user + ")");
        long qv = insert(st, "INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + user + ")");
        long eq = insert(st, "INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + exam + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        long session = insert(st, "INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, "
                + "max_attempts, duration_minutes, created_by, opened_at) VALUES ("
                + exam + ",'SQ" + nano + "','t','OPEN',now()-interval '1 hour',now()+interval '2 hours',2,60,"
                + user + ",now()-interval '1 hour')");
        long attempt = insert(st, "INSERT INTO attempts (exam_session_id, user_id, attempt_number, started_at, deadline_at) "
                + "VALUES (" + session + "," + user + ",1,now(),now()+interval '1 hour')");
        long aq = insert(st, "INSERT INTO attempt_questions (attempt_id, exam_question_id, question_type, "
                + "default_points, display_order) VALUES (" + attempt + "," + eq + ",'SINGLE_CHOICE',1,0)");
        return new Fixture(session, attempt, aq);
    }

    private static String eventInsert(String eventId, long attemptId, String type, String source,
            String clientOccurredAt, String clientSequence) {
        return "INSERT INTO attempt_activity_events (event_id, attempt_id, event_type, source, "
                + "client_occurred_at, client_sequence) VALUES ('" + eventId + "'," + attemptId + ",'"
                + type + "','" + source + "'," + clientOccurredAt + "," + clientSequence + ")";
    }

    // ==================================================================
    // staged-flyway helpers (V32 matrix pattern)
    // ==================================================================

    private String createDatabase(String prefix) throws SQLException {
        String dbName = prefix + "_" + System.nanoTime();
        try (Connection c = java.sql.DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
        }
        return dbName;
    }

    private String urlFor(String dbName) {
        return pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + dbName);
    }

    private void dropDatabase(String dbName) throws SQLException {
        try (Connection c = java.sql.DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
        }
    }

    private static void execute(Statement st, String sql) throws SQLException {
        st.execute(sql);
    }

    private static long insert(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql + " RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
