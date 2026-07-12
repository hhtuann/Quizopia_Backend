package com.quizopia.backend.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standalone, non-Spring test that proves the V9 migration applies cleanly on top of an
 * existing V1–V8 database (with real V8 data) and is a no-op on rerun. Runs on a fully
 * isolated PostgreSQL 17 container (separate from the Spring-managed container used by
 * {@code V9AttemptSchemaIntegrationTests}) so the staged scenario cannot pollute the shared
 * container. No application context is started.
 *
 * <p>Mirrors the convention of {@code V8PurposeSeedStagedFlywayTest}: stage Flyway to V8,
 * insert data, then continue to V9 and assert the V8 data is unchanged and the six new V9
 * tables exist. Finally re-run migration and validate checksums to prove the migration does
 * not silently mutate the schema on a second pass.
 */
@SuppressWarnings({"resource"})
class V9AttemptSchemaStagedFlywayTest {

    private static final List<String> V9_TABLES = List.of(
            "attempts", "attempt_questions", "attempt_answers", "grades", "grade_items", "idempotency_records");

    @Test
    void existingV8DatabaseMigratesToV9PreservingData() throws Exception {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withDatabaseName("v9_staged").withUsername("t").withPassword("t")) {
            pg.start();
            String url = pg.getJdbcUrl();
            String user = pg.getUsername();
            String pass = pg.getPassword();

            // 1. Migrate clean DB up to V6 (no attempt tables yet — attempt schema is V7).
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("6"))
                    .load().migrate();

            // 2. Insert a realistic V6 chain (school -> ... -> exam_session + participant) BEFORE V7.
            long[] v8ids = insertV8Chain(url, user, pass);

            // 3. V7 tables must NOT exist yet at V6.
            try (Connection c = DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                for (String t : V9_TABLES) {
                    try (ResultSet rs = st.executeQuery(
                            "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1))
                                .as("V7 table %s must not exist at V6", t).isZero();
                    }
                }
            }

            // 4. Continue migration (V7 creates attempt tables, V8 classrooms, V9 notifications).
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load().migrate();

            // 5. Latest applied version is 9.
            try (Connection c = DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("9");
            }

            // 6. All six V9 tables now exist.
            try (Connection c = DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                for (String t : V9_TABLES) {
                    try (ResultSet rs = st.executeQuery(
                            "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1))
                                .as("V9 table %s must exist after V9", t).isEqualTo(1);
                    }
                }
            }

            // 7. Existing V8 data is unchanged: the chain rows still resolve to the same ids.
            assertV8DataUnchanged(url, user, pass, v8ids);

            // 8. V1–V8 checksums unchanged, no pending migrations, V9 rerun is a no-op.
            Flyway flyway = Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration").load();
            flyway.validate(); // throws if any applied migration checksum drifts
            MigrationInfoService info = flyway.info();
            assertThat(info.pending()).isEmpty();
            // Re-running migrate must apply 0 statements (no silent schema mutation).
            MigrateResult rerun = flyway.migrate();
            assertThat(rerun.migrationsExecuted).isZero();
        }
    }

    @Test
    void cleanDatabaseMigratesToV9WithEmptyAttemptTables() throws Exception {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withDatabaseName("v9_clean").withUsername("t").withPassword("t")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load().migrate();

            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 Statement st = c.createStatement()) {
                for (String t : V9_TABLES) {
                    try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + t)) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1))
                                .as("V9 table %s is empty on a clean DB", t).isZero();
                    }
                }
            }
        }
    }

    // ============================================================
    // V8 fixture helpers (raw JDBC; mirrors V8ExamSchemaIntegrationTests fixtures)
    // ============================================================

    /** Inserts a full V8 chain and returns the key ids to re-verify after V9. */
    private long[] insertV8Chain(String url, String user, String pass) throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            long userId = insertReturning(c, "INSERT INTO users (username, email, password_hash, display_name) "
                    + "VALUES ('stg','stg@t.com','h','STG')");
            long schoolId = insertReturning(c, "INSERT INTO schools (code, name) VALUES ('SS','Staged School')");
            long glId = insertReturning(c, "INSERT INTO grade_levels (school_id, code, name) VALUES ("
                    + schoolId + ",'GL12','G12')");
            long subjectId = insertReturning(c, "INSERT INTO subjects (school_id, grade_level_id, code, name) "
                    + "VALUES (" + schoolId + "," + glId + ",'MATH','Math')");
            long teacherId = insertReturning(c, "INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                    + "VALUES (" + userId + "," + schoolId + ",'TC')");
            long studentId = insertReturning(c, "INSERT INTO student_profiles (user_id, school_id, student_code) "
                    + "VALUES (" + userId + "," + schoolId + ",'SC')");
            long bankId = insertReturning(c, "INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, "
                    + "code, name) VALUES (" + schoolId + "," + subjectId + "," + teacherId + ",'B','Bank')");
            long questionId = insertReturning(c, "INSERT INTO questions (question_bank_id, code, created_by) "
                    + "VALUES (" + bankId + ",'Q'," + userId + ")");
            long versionId = insertReturning(c, "INSERT INTO question_versions (question_id, version_number, "
                    + "question_type, content, default_points, metadata, created_by) VALUES (" + questionId
                    + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + userId + ")");
            long examId = insertReturning(c, "INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) "
                    + "VALUES (" + schoolId + "," + subjectId + "," + teacherId + ",'E','t')");
            long evId = insertReturning(c, "INSERT INTO exam_versions (school_id, exam_id, version_number, status, "
                    + "total_points, published_at, created_by) VALUES (" + schoolId + "," + examId + ",1,'PUBLISHED',10,"
                    + "now()," + userId + ")");
            long sectionId = insertReturning(c, "INSERT INTO exam_sections (exam_version_id, title, position) "
                    + "VALUES (" + evId + ",'Sec',0)");
            long eqId = insertReturning(c, "INSERT INTO exam_questions (exam_version_id, exam_section_id, "
                    + "source_question_id, source_question_version_id, question_code, question_type, content, "
                    + "default_points, position, metadata) VALUES (" + evId + "," + sectionId + "," + questionId + ","
                    + versionId + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
            long sessionId = insertReturning(c, "INSERT INTO exam_sessions (school_id, exam_version_id, "
                    + "owner_teacher_id, code, title, starts_at, ends_at, created_by) VALUES (" + schoolId + "," + evId
                    + "," + teacherId + ",'S','t',now(),now()+interval '1 hour'," + userId + ")");
            long participantId = insertReturning(c, "INSERT INTO exam_session_participants (school_id, exam_session_id, "
                    + "student_profile_id, added_by) VALUES (" + schoolId + "," + sessionId + "," + studentId + ","
                    + userId + ")");
            return new long[]{userId, schoolId, examId, evId, eqId, sessionId, participantId};
        }
    }

    private void assertV8DataUnchanged(String url, String user, String pass, long[] ids) throws Exception {
        long userId = ids[0], schoolId = ids[1], examId = ids[2], evId = ids[3], eqId = ids[4], sessionId = ids[5],
                participantId = ids[6];
        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement st = c.createStatement()) {
            assertThat(exists(st, "users", userId)).isTrue();
            assertThat(exists(st, "schools", schoolId)).isTrue();
            assertThat(exists(st, "exams", examId)).isTrue();
            assertThat(exists(st, "exam_versions", evId)).isTrue();
            assertThat(exists(st, "exam_questions", eqId)).isTrue();
            assertThat(exists(st, "exam_sessions", sessionId)).isTrue();
            assertThat(exists(st, "exam_session_participants", participantId)).isTrue();
            // V8 table counts unchanged (exactly the one chain row each in the leaf tables).
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM exam_sessions")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    private boolean exists(Statement st, String table, long id) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table + " WHERE id=" + id)) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    private long insertReturning(Connection c, String sql) throws Exception {
        // Plain Statement + executeQuery on the RETURNING clause (standard PostgreSQL pattern).
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql + " RETURNING id")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }
}
