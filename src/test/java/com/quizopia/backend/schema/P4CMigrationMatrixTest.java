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
 * V38 P4-C AI-proctoring migration matrix (issues #88/#90), following the
 * staged-flyway house pattern ({@code P4AMigrationMatrixTest}): a FRESH
 * database inside the shared container migrates V1..V38, then plain JDBC
 * proves the schema contracts the P4-C services rely on:
 *
 * <ul>
 *   <li>{@code monitoring_ai_detections}: event_id UNIQUE (idempotency
 *       identity), provenance/event_type/runtime CHECKs admit exactly the
 *       contract values, quarantined rows REQUIRE a reason and clean rows
 *       FORBID one, payload must be a JSON array;</li>
 *   <li>{@code monitoring_ai_alerts}: the partial unique index enforces
 *       ONE OPEN window per (attempt_id, alert_type) — a second OPEN row
 *       is rejected, while a CLOSED window frees the slot for a new one
 *       (the §5 gap semantics);</li>
 *   <li>{@code attempt_activity_events}: AI_ALERT_RAISED /
 *       AI_ALERT_UPDATED admitted as SERVER rows (AI_ALERT_CREATED stays
 *       rejected);</li>
 *   <li>V38 permissions: AI_DETECTION_INGEST granted to exactly STUDENT +
 *       TEACHER, AI_ALERTS_READ to exactly TEACHER;</li>
 *   <li><b>No grade-domain linkage (CONTRACT §4.4/§7):</b> neither P4-C
 *       table has any FK into grades/grade_items — AI observations can
 *       never touch scores by design.</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class P4CMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    @Test
    void freshDatabaseEnforcesP4CAiProctoringContracts() throws Exception {
        String dbName = createDatabase("p4cmx");
        try {
            String url = urlFor(dbName);
            Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(
                    url, pg.getUsername(), pg.getPassword());
                 Statement st = c.createStatement()) {
                assertMigrationChainReachedV38(st);
                assertDetectionContracts(st);
                assertAlertWindowUniqueness(st);
                assertAiActivityTypesAdmitted(st);
                assertPermissions(st);
                assertNoGradeTableLinkage(st);
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // monitoring_ai_detections (V38)
    // ==================================================================

    private void assertDetectionContracts(Statement st) throws SQLException {
        Fixture f = scaffold(st);
        insert(st, detectionInsert(f.attemptId, UUID.randomUUID().toString(),
                "CLIENT_MODEL", "PHONE_OBSERVED", "false", "NULL", "'[]'::jsonb", "NULL"));

        // event_id is the idempotency identity — a second row with the same
        // UUID fails.
        String uuid = UUID.randomUUID().toString();
        insert(st, detectionInsert(f.attemptId, uuid, "CLIENT_MODEL", "PHONE_OBSERVED",
                "false", "NULL", "'[]'::jsonb", "NULL"));
        assertThatThrownBy(() -> execute(st, detectionInsert(f.attemptId, uuid,
                "WORKER_VERIFIED", "SCREEN_OBSERVED", "false", "NULL", "'[]'::jsonb", "NULL")))
                .as("UNIQUE (event_id) enforced")
                .isInstanceOf(SQLException.class);

        // provenance CHECK: only CLIENT_MODEL / WORKER_VERIFIED.
        assertThatThrownBy(() -> execute(st, detectionInsert(f.attemptId,
                UUID.randomUUID().toString(), "SERVER", "PHONE_OBSERVED", "false", "NULL",
                "'[]'::jsonb", "NULL")))
                .as("provenance CHECK admits only CLIENT_MODEL|WORKER_VERIFIED")
                .isInstanceOf(SQLException.class);
        // event_type CHECK: exactly the four OBSERVED types; the DETECTED
        // (server-aggregated) vocabulary is rejected here.
        for (String badType : new String[]{"PHONE_DETECTED", "GAZE_LOST", "AI_ALERT_RAISED"}) {
            assertThatThrownBy(() -> execute(st, detectionInsert(f.attemptId,
                    UUID.randomUUID().toString(), "CLIENT_MODEL", badType, "false", "NULL",
                    "'[]'::jsonb", "NULL")))
                    .as("event_type %s must be rejected by the CHECK allowlist", badType)
                    .isInstanceOf(SQLException.class);
        }
        assertThatThrownBy(() -> execute(st, detectionInsert(f.attemptId,
                UUID.randomUUID().toString(), "CLIENT_MODEL", "PHONE_OBSERVED", "false", "NULL",
                "'[]'::jsonb", "NULL").replace("'ORT_WEB_WASM'", "'ORT_NODE'")))
                .as("runtime CHECK admits only the three contract runtimes")
                .isInstanceOf(SQLException.class);
        // quarantine pair: quarantined ⇒ reason, clean ⇒ no reason.
        assertThatThrownBy(() -> execute(st, detectionInsert(f.attemptId,
                UUID.randomUUID().toString(), "CLIENT_MODEL", "PHONE_OBSERVED", "true", "NULL",
                "'[]'::jsonb", "1500")))
                .as("quarantined row without a reason must be rejected")
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(st, detectionInsert(f.attemptId,
                UUID.randomUUID().toString(), "CLIENT_MODEL", "PHONE_OBSERVED", "false",
                "'ATTEMPT_NOT_IN_PROGRESS'", "'[]'::jsonb", "NULL")))
                .as("clean row with a quarantine reason must be rejected")
                .isInstanceOf(SQLException.class);
        // payload must be a JSON array (the detections array).
        assertThatThrownBy(() -> execute(st, detectionInsert(f.attemptId,
                UUID.randomUUID().toString(), "CLIENT_MODEL", "PHONE_OBSERVED", "false", "NULL",
                "'{\"k\":1}'::jsonb", "NULL")))
                .as("payload CHECK requires a JSON array")
                .isInstanceOf(SQLException.class);
    }

    // ==================================================================
    // monitoring_ai_alerts (V38) — one OPEN window per attempt+type
    // ==================================================================

    private void assertAlertWindowUniqueness(Statement st) throws SQLException {
        Fixture f = scaffold(st);
        insert(st, alertInsert(f.attemptId, "PHONE_DETECTED", "OPEN"));
        // a second OPEN window for the same pair is rejected…
        assertThatThrownBy(() -> execute(st, alertInsert(f.attemptId, "PHONE_DETECTED", "OPEN")))
                .as("partial unique index: one OPEN alert per (attempt_id, alert_type)")
                .isInstanceOf(SQLException.class);
        // …while a different type coexists…
        insert(st, alertInsert(f.attemptId, "SCREEN_DETECTED", "OPEN"));
        // …and closing the window frees the slot (§5 gap semantics).
        execute(st, "UPDATE monitoring_ai_alerts SET status='CLOSED' "
                + "WHERE attempt_id=" + f.attemptId + " AND alert_type='PHONE_DETECTED'");
        insert(st, alertInsert(f.attemptId, "PHONE_DETECTED", "OPEN"));
        // alert_type CHECK: exactly the four aggregated types.
        assertThatThrownBy(() -> execute(st, alertInsert(f.attemptId, "PHONE_OBSERVED", "OPEN")))
                .as("alert_type CHECK admits only the DETECTED vocabulary")
                .isInstanceOf(SQLException.class);
        // status CHECK: OPEN/CLOSED only.
        assertThatThrownBy(() -> execute(st, alertInsert(f.attemptId,
                "MULTIPLE_PERSONS_DETECTED", "ARCHIVED")))
                .as("status CHECK admits only OPEN|CLOSED")
                .isInstanceOf(SQLException.class);
    }

    // ==================================================================
    // activity-event types (V38 amendment of the V34/V37 CHECK)
    // ==================================================================

    private void assertAiActivityTypesAdmitted(Statement st) throws SQLException {
        Fixture f = scaffold(st);
        for (String type : new String[]{"AI_ALERT_RAISED", "AI_ALERT_UPDATED"}) {
            insert(st, "INSERT INTO attempt_activity_events (event_id, attempt_id, event_type, "
                    + "source) VALUES ('" + UUID.randomUUID() + "'," + f.attemptId
                    + ",'" + type + "','SERVER')");
        }
        // The never-introduced AI types stay rejected.
        assertThatThrownBy(() -> execute(st, "INSERT INTO attempt_activity_events (event_id, "
                + "attempt_id, event_type, source) VALUES ('" + UUID.randomUUID() + "',"
                + f.attemptId + ",'AI_ALERT_CREATED','SERVER')"))
                .as("AI_ALERT_CREATED is not an admitted type")
                .isInstanceOf(SQLException.class);
    }

    // ==================================================================
    // V38 permissions
    // ==================================================================

    private void assertPermissions(Statement st) throws SQLException {
        List<String> ingestRoles = rows(st, """
                        SELECT r.code FROM role_permissions rp
                        JOIN permissions p ON p.id = rp.permission_id
                        JOIN roles r ON r.id = rp.role_id
                        WHERE p.code = 'AI_DETECTION_INGEST'
                        ORDER BY r.code
                        """);
        assertThat(ingestRoles)
                .as("AI_DETECTION_INGEST is learner-owned (STUDENT + TEACHER)")
                .containsExactly("STUDENT", "TEACHER");

        List<String> readRoles = rows(st, """
                        SELECT r.code FROM role_permissions rp
                        JOIN permissions p ON p.id = rp.permission_id
                        JOIN roles r ON r.id = rp.role_id
                        WHERE p.code = 'AI_ALERTS_READ'
                        ORDER BY r.code
                        """);
        assertThat(readRoles)
                .as("AI_ALERTS_READ is TEACHER-only")
                .containsExactly("TEACHER");
    }

    // ==================================================================
    // grading-domain isolation (CONTRACT §4.4/§7)
    // ==================================================================

    private void assertNoGradeTableLinkage(Statement st) throws SQLException {
        List<String> referenced = rows(st, """
                        SELECT cl.relname FROM pg_constraint c
                        JOIN pg_class t ON t.oid = c.conrelid
                        JOIN pg_class cl ON cl.oid = c.confrelid
                        WHERE t.relname IN ('monitoring_ai_detections', 'monitoring_ai_alerts')
                          AND c.contype = 'f'
                        """);
        assertThat(referenced)
                .as("P4-C tables reference only attempts/exam_sessions/users/"
                        + "monitoring_media_bindings — never the grading domain")
                .containsOnly("attempts", "exam_sessions", "users",
                        "monitoring_media_bindings");
    }

    private void assertMigrationChainReachedV38(Statement st) throws SQLException {
        assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                .as("migration chain reached V38")
                .containsExactly("38");
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private record Fixture(long sessionId, long attemptId) {}

    /** user -> PUBLISHED exam -> OPEN session -> attempt. */
    private Fixture scaffold(Statement st) throws SQLException {
        long nano = System.nanoTime();
        long user = insert(st, "INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('p4c" + nano + "','p4c" + nano + "@t.example.com','h','P4C')");
        long exam = insert(st, "INSERT INTO exams (created_by_user_id, code, title, status, "
                + "published_at, total_points) VALUES (" + user + ",'P4C" + nano
                + "','P4C exam','PUBLISHED',now(),1)");
        long session = insert(st, "INSERT INTO exam_sessions (exam_id, code, title, status, "
                + "starts_at, ends_at, max_attempts, duration_minutes, created_by, opened_at) "
                + "VALUES (" + exam + ",'SS" + nano + "','t','OPEN',now()-interval '1 hour',"
                + "now()+interval '2 hours',2,60," + user + ",now()-interval '1 hour')");
        long attempt = insert(st, "INSERT INTO attempts (exam_session_id, user_id, "
                + "attempt_number, started_at, deadline_at) VALUES (" + session + "," + user
                + ",1,now(),now()+interval '1 hour')");
        return new Fixture(session, attempt);
    }

    private String detectionInsert(long attemptId, String eventId, String provenance,
            String eventType, String quarantined, String reason, String payload, String lateMs) {
        return "INSERT INTO monitoring_ai_detections (attempt_id, exam_session_id, user_id, "
                + "event_id, provenance, event_type, model_id, model_version, runtime, "
                + "observed_at, quarantined, quarantine_reason, frame_width, frame_height, "
                + "processing_ms, payload, late_ms) "
                + "SELECT " + attemptId + ", exam_session_id, user_id, '" + eventId + "', '"
                + provenance + "', '" + eventType + "', 'yolo26s', '8.4.64-onnx', "
                + "'ORT_WEB_WASM', now(), " + quarantined + ", " + reason + ", 640, 480, 50, "
                + payload + ", " + lateMs
                + " FROM attempts WHERE id = " + attemptId;
    }

    private String alertInsert(long attemptId, String alertType, String status) {
        return "INSERT INTO monitoring_ai_alerts (attempt_id, exam_session_id, user_id, "
                + "alert_type, status, first_detected_at, last_detected_at, detection_count, "
                + "model_id, model_version, provenance) "
                + "SELECT " + attemptId + ", exam_session_id, user_id, '" + alertType + "', '"
                + status + "', now(), now(), 1, 'yolo26s', '8.4.64-onnx', 'CLIENT_MODEL' "
                + "FROM attempts WHERE id = " + attemptId;
    }

    private long insert(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql + " RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private int execute(Statement st, String sql) throws SQLException {
        return st.executeUpdate(sql);
    }

    private List<String> rows(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }

    private String createDatabase(String dbName) throws SQLException {
        try (Connection c = pg.createConnection("");
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
        }
        return dbName;
    }

    private String urlFor(String dbName) {
        return pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + dbName);
    }

    private void dropDatabase(String dbName) throws SQLException {
        try (Connection c = pg.createConnection("");
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
        }
    }
}
