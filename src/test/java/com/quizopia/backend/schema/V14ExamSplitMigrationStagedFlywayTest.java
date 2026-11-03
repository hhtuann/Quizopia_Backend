package com.quizopia.backend.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staged-migration proof for the Task B redesign (issue #2 §3): a legacy exam
 * with 2 PUBLISHED versions + 1 DRAFT version splits into exactly 3 exams —
 * the canonical (earliest published) version keeps the original exam id, every
 * other version becomes a distinct new exam, sessions/attempts follow their
 * exact version, durations materialize, and no row is lost or misassigned.
 *
 * <p>Stages: migrate to V12 (pre-redesign) → insert legacy data → migrate to
 * V15 → verify.
 */
class V14ExamSplitMigrationStagedFlywayTest {

    private record Legacy(long examId, long v1, long v2, long v3,
                          long sess1, long sess2,
                          long attemptV1, long attemptV2) {
    }

    @Test
    void multiVersionExamSplitsDeterministicallyWithoutDataLoss() throws Exception {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withDatabaseName("v14_split_staged").withUsername("t").withPassword("t")) {
            pg.start();
            String url = pg.getJdbcUrl();
            String user = pg.getUsername();
            String pass = pg.getPassword();

            // Stage 1: pre-redesign schema (through V12).
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("12").load().migrate();

            Legacy legacy;
            Map<String, String> before;
            try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
                long userId = insert(st, "INSERT INTO users (username, email, password_hash, display_name) "
                        + "VALUES ('split-user','split@t.com','h','Split User')");
                insert(st, "INSERT INTO users (username, email, password_hash, display_name) "
                        + "VALUES ('split-other','split-other@t.com','h','Other Owner')");
                long schoolId = insert(st, "INSERT INTO schools (code, name) VALUES ('SPLIT-SCH','Split School')");
                long gl = insert(st, "INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
                long subjectId = insert(st, "INSERT INTO subjects (school_id, grade_level_id, code, name) "
                        + "VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
                long tp = insert(st, "INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                        + "VALUES (" + userId + "," + schoolId + ",'TP-SPLIT')");
                long sp = insert(st, "INSERT INTO student_profiles (user_id, school_id, student_code) "
                        + "VALUES (" + userId + "," + schoolId + ",'SP-SPLIT')");
                long bank = insert(st, "INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) "
                        + "VALUES (" + schoolId + "," + subjectId + "," + tp + ",'SB','Split Bank')");
                long q1 = insert(st, "INSERT INTO questions (question_bank_id, code, created_by) "
                        + "VALUES (" + bank + ",'sq1'," + userId + ")");
                insert(st, "INSERT INTO question_versions (question_id, version_number, question_type, content, "
                        + "difficulty, default_points, metadata, created_by) "
                        + "VALUES (" + q1 + ",1,'SINGLE_CHOICE','split q','MEDIUM',1,'{}'::jsonb," + userId + ")");
                long q2 = insert(st, "INSERT INTO questions (question_bank_id, code, created_by) "
                        + "VALUES (" + bank + ",'sq2'," + userId + ")");
                insert(st, "INSERT INTO question_versions (question_id, version_number, question_type, content, "
                        + "difficulty, default_points, metadata, created_by) "
                        + "VALUES (" + q2 + ",1,'SINGLE_CHOICE','split q2','MEDIUM',2,'{}'::jsonb," + userId + ")");

                long examId = insert(st, "INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title, status) "
                        + "VALUES (" + schoolId + "," + subjectId + "," + tp + ",'SPLIT-EX','Split Exam','READY')");

                // v1 PUBLISHED (canonical — earliest published), v2 PUBLISHED, v3 DRAFT.
                long v1 = insert(st, examVersion(schoolId, examId, 1, "PUBLISHED", 30, userId));
                long v2 = insert(st, examVersion(schoolId, examId, 2, "PUBLISHED", 45, userId));
                long v3 = insert(st, examVersion(schoolId, examId, 3, "DRAFT", 60, userId));
                st.executeUpdate("UPDATE exams SET description='Original exam description' WHERE id=" + examId);

                long s1 = insert(st, "INSERT INTO exam_sections (exam_version_id, code, title, position) "
                        + "VALUES (" + v1 + ",'A','Section A',0)");
                long s2 = insert(st, "INSERT INTO exam_sections (exam_version_id, code, title, position) "
                        + "VALUES (" + v2 + ",'A2','Section A2',0)");
                long s3 = insert(st, "INSERT INTO exam_sections (exam_version_id, code, title, position) "
                        + "VALUES (" + v3 + ",'A3','Section A3',0)");
                long eq1 = insert(st, "INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, "
                        + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                        + "VALUES (" + v1 + "," + s1 + "," + q1 + "," + versionOf(st, q1)
                        + ",'sq1','SINGLE_CHOICE','split q',1,0,'{}'::jsonb)");
                long eq2 = insert(st, "INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, "
                        + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                        + "VALUES (" + v2 + "," + s2 + "," + q2 + "," + versionOf(st, q2)
                        + ",'sq2','SINGLE_CHOICE','split q2',2,0,'{}'::jsonb)");
                insert(st, "INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, "
                        + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                        + "VALUES (" + v3 + "," + s3 + "," + q1 + "," + versionOf(st, q1)
                         + ",'sq1d','SINGLE_CHOICE','split q draft',1,0,'{}'::jsonb)");

                for (long question : new long[]{eq1, eq2}) {
                    for (int position = 0; position < 2; position++) {
                        insert(st, "INSERT INTO exam_question_options (exam_question_id,option_key,content,is_correct,position) "
                                + "VALUES (" + question + ",'" + (position == 0 ? "A" : "B") + "','Question "
                                + question + " option " + position + "'," + (position == 0) + "," + position + ")");
                    }
                }

                // Sessions on v1 (null duration → materialize 30) and v2 (override 15).
                long sess1 = insert(st, "INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, "
                        + "status, starts_at, ends_at, created_by, visibility, max_attempts, duration_minutes, "
                        + "opened_at, closed_at) "
                        + "VALUES (" + schoolId + "," + v1 + "," + tp + ",'SES1','S1','CLOSED',"
                        + "now()-interval '2 hours',now()-interval '1 hour'," + userId + ",'CLASS_RESTRICTED',1,NULL,"
                        + "now()-interval '2 hours',now()-interval '1 hour')");
                long sess2 = insert(st, "INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, "
                        + "status, starts_at, ends_at, created_by, visibility, max_attempts, duration_minutes, "
                        + "opened_at, closed_at) "
                        + "VALUES (" + schoolId + "," + v2 + "," + tp + ",'SES2','S2','CLOSED',"
                        + "now()-interval '2 hours',now()-interval '1 hour'," + userId + ",'CLASS_RESTRICTED',1,15,"
                        + "now()-interval '2 hours',now()-interval '1 hour')");
                // v3 (DRAFT) has NO sessions — legal.

                long attemptV1 = insert(st, "INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, status, started_at, deadline_at, submitted_at, submission_idempotency_key) "
                        + "VALUES (" + schoolId + "," + sess1 + "," + sp + "," + v1 + ",1,'SUBMITTED',"
                        + "now()-interval '2 hours',now()-interval '90 minutes',now()-interval '95 minutes','K1')");
                long attemptV2 = insert(st, "INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, status, started_at, deadline_at, submitted_at, submission_idempotency_key) "
                        + "VALUES (" + schoolId + "," + sess2 + "," + sp + "," + v2 + ",1,'SUBMITTED',"
                        + "now()-interval '2 hours',now()-interval '105 minutes',now()-interval '100 minutes','K2')");
                for (long a : new long[]{attemptV1, attemptV2}) {
                    long aq = insert(st, "INSERT INTO attempt_questions (attempt_id, exam_question_id, question_type, "
                            + "default_points, display_order, option_order) VALUES (" + a + ","
                            + (a == attemptV1 ? eq1 : eq2) + ",'SINGLE_CHOICE',1,0,'[\"A\",\"B\"]'::jsonb)");
                    insert(st, "INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number) "
                            + "VALUES (" + a + "," + aq + ",'{\"selected\":[\"A\"]}'::jsonb,1)");
                    long grade = insert(st, "INSERT INTO grades (attempt_id, automatic_score, final_score, max_score, "
                            + "status, graded_at, graded_by) VALUES (" + a + ",1,1,1,'AUTO_GRADED',now()," + userId + ")");
                    insert(st, "INSERT INTO grade_items (grade_id, attempt_id, attempt_question_id, awarded_points, "
                            + "max_points, is_correct, grading_details) VALUES (" + grade + "," + a + "," + aq + ",1,1,true,'{}'::jsonb)");
                }

                legacy = new Legacy(examId, v1, v2, v3, sess1, sess2, attemptV1, attemptV2);
                st.execute(script("p1-b-preflight.sql"));
                before = evidence(st, "p1-b-before.sql");
                assertThat(before.keySet().stream().filter(k -> k.startsWith("options:")).count()).isEqualTo(4);
            }

            // Probe fail-fast behavior on this disposable database before the
            // successful cutover. V14 must roll back without changing history.
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration").target("13").load().migrate();
            try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE exam_sessions SET exam_version_id=" + legacy.v3() + " WHERE id=" + legacy.sess1());
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.execute(script("p1-b-preflight.sql")))
                        .hasMessageContaining("non-published");
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration").load().migrate()).hasStackTraceContaining("non-PUBLISHED");
            try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
                assertThat(count(st, "SELECT count(*) FROM legacy_exam_content_map")).isZero();
                st.executeUpdate("UPDATE exam_sessions SET exam_version_id=" + legacy.v1() + " WHERE id=" + legacy.sess1());
                // Simulate a historically corrupted dump; only in disposable test DB.
                st.execute("SET session_replication_role=replica");
                st.executeUpdate("UPDATE exam_sections SET exam_version_id=999999999 WHERE exam_version_id=" + legacy.v3());
                st.execute("SET session_replication_role=origin");
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration").load().migrate()).hasStackTraceContaining("missing exam_version");
            try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
                assertThat(count(st, "SELECT count(*) FROM legacy_exam_content_map")).isZero();
                st.executeUpdate("UPDATE exam_sections SET exam_version_id=" + legacy.v3() + " WHERE exam_version_id=999999999");
            }

            // Stage 2: run the redesign migrations through latest.
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration").load().migrate();

            try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
                st.execute(script("p1-b-postflight.sql"));
                assertThat(evidence(st, "p1-b-after.sql")).containsExactlyInAnyOrderEntriesOf(before);
                Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration").load().validate();
                // Prove the evidence detects score drift even when all counts stay equal.
                st.executeUpdate("UPDATE grades SET final_score=0 WHERE attempt_id=" + legacy.attemptV1());
                assertThat(evidence(st, "p1-b-after.sql")).isNotEqualTo(before);
                st.executeUpdate("UPDATE grades SET final_score=1 WHERE attempt_id=" + legacy.attemptV1());
                assertThat(evidence(st, "p1-b-after.sql")).containsExactlyInAnyOrderEntriesOf(before);
                // 0. The untargeted migrate ran through V16 (user-based attempts) — the
                //    staged attempts carry user_id (V12 backfill) so the V16 constraint
                //    swap + verification pass on the legacy data.
                assertThat(scalar(st, "SELECT version FROM flyway_schema_history WHERE success "
                        + "ORDER BY installed_rank DESC LIMIT 1")).isEqualTo(17);

                // 1. Exactly 3 exams for the legacy one; canonical keeps the original id.
                Map<Long, String> examsById = new LinkedHashMap<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT id, status FROM exams WHERE code LIKE 'SPLIT-EX%'")) {
                    while (rs.next()) {
                        examsById.put(rs.getLong(1), rs.getString(2));
                    }
                }
                assertThat(examsById).hasSize(3);
                assertThat(examsById).containsKey(legacy.examId());
                assertThat(examsById.get(legacy.examId())).isEqualTo("PUBLISHED"); // canonical = v1 PUBLISHED

                // 2. Mapping table: 3 rows, canonical v1 → original id, others → distinct new exams.
                Map<Long, long[]> map = new LinkedHashMap<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT legacy_version_id, new_exam_id, is_canonical FROM legacy_exam_content_map "
                                + "WHERE legacy_exam_id = " + legacy.examId())) {
                    while (rs.next()) {
                        map.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getBoolean(3) ? 1 : 0});
                    }
                }
                assertThat(map).containsOnlyKeys(legacy.v1(), legacy.v2(), legacy.v3());
                assertThat(map.get(legacy.v1())).isEqualTo(new long[]{legacy.examId(), 1});
                assertThat(map.get(legacy.v2())[1]).isZero();
                assertThat(map.get(legacy.v3())[1]).isZero();
                long splitV2 = map.get(legacy.v2())[0];
                long splitV3 = map.get(legacy.v3())[0];
                assertThat(splitV2).isNotEqualTo(splitV3);

                // Review regression: split display metadata must be evidenced too.
                c.setAutoCommit(false);
                var probe = c.setSavepoint();
                st.executeUpdate("UPDATE exams SET title='Undetected split title drift' WHERE id=" + splitV2);
                assertThat(evidence(st, "p1-b-after.sql")).as("split title mutation detected").isNotEqualTo(before);
                c.rollback(probe);
                for (String assignment : new String[]{
                        "created_by_user_id=(SELECT id FROM users WHERE username='split-other')",
                        "description='Changed split description'", "subject_id=NULL", "owner_teacher_id=NULL"}) {
                    probe = c.setSavepoint();
                    st.executeUpdate("UPDATE exams SET " + assignment + " WHERE id=" + splitV2);
                    assertThat(evidence(st, "p1-b-after.sql")).as("split metadata drift: " + assignment).isNotEqualTo(before);
                    c.rollback(probe);
                }
                // Each probe is rolled back independently, preserving identity/timestamps.
                for (String mutation : new String[]{
                        "UPDATE exam_question_options SET content='Changed option' WHERE id=(SELECT min(id) FROM exam_question_options)",
                        "UPDATE exam_question_options SET is_correct=NOT is_correct WHERE id=(SELECT max(id) FROM exam_question_options)",
                        "DELETE FROM exam_question_options WHERE id=(SELECT max(id) FROM exam_question_options)"}) {
                    probe = c.setSavepoint();
                    assertThat(st.executeUpdate(mutation)).isEqualTo(1);
                    assertThat(evidence(st, "p1-b-after.sql")).as("option drift: " + mutation).isNotEqualTo(before);
                    c.rollback(probe);
                }
                assertThat(evidence(st, "p1-b-after.sql")).containsExactlyInAnyOrderEntriesOf(before);
                c.setAutoCommit(true);

                // 3. Sections follow their exact version; none orphaned.
                assertThat(count(st, "SELECT count(*) FROM exam_sections WHERE exam_id = " + legacy.examId())).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM exam_sections WHERE exam_id = " + splitV2)).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM exam_sections WHERE exam_id = " + splitV3)).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM exam_sections WHERE exam_id IS NULL")).isZero();

                // 4. Sessions follow their exact version — never "latest".
                assertThat(scalar(st, "SELECT exam_id FROM exam_sessions WHERE id = " + legacy.sess1()))
                        .isEqualTo(legacy.examId());
                assertThat(scalar(st, "SELECT exam_id FROM exam_sessions WHERE id = " + legacy.sess2()))
                        .isEqualTo(splitV2);

                // 5. Duration materialization: NULL override → version default 30;
                //    explicit override 15 stays 15.
                assertThat(scalar(st, "SELECT duration_minutes FROM exam_sessions WHERE id = " + legacy.sess1())).isEqualTo(30);
                assertThat(scalar(st, "SELECT duration_minutes FROM exam_sessions WHERE id = " + legacy.sess2())).isEqualTo(15);

                // 6. Attempts/answers/grades: row ids preserved, exam linkage via session.
                assertThat(count(st, "SELECT count(*) FROM attempts")).isEqualTo(2);
                assertThat(scalar(st, "SELECT exam_id FROM exam_sessions s JOIN attempts a ON a.exam_session_id = s.id "
                        + "WHERE a.id = " + legacy.attemptV1())).isEqualTo(legacy.examId());
                assertThat(scalar(st, "SELECT exam_id FROM exam_sessions s JOIN attempts a ON a.exam_session_id = s.id "
                        + "WHERE a.id = " + legacy.attemptV2())).isEqualTo(splitV2);
                assertThat(count(st, "SELECT count(*) FROM attempt_answers")).isEqualTo(2);
                assertThat(count(st, "SELECT count(*) FROM grades")).isEqualTo(2);
                assertThat(count(st, "SELECT count(*) FROM grade_items")).isEqualTo(2);
                assertThat(scalar(st, "SELECT final_score FROM grades WHERE attempt_id = " + legacy.attemptV1())).isEqualTo(1);

                // 7. Canonical exam carries the published content columns; the split
                //    of the DRAFT version is a DRAFT exam with 0 points.
                assertThat(scalar(st, "SELECT duration_minutes FROM exams WHERE id = " + legacy.examId())).isEqualTo(30);
                assertThat(count(st, "SELECT count(*) FROM exams WHERE id = " + legacy.examId()
                        + " AND published_at IS NOT NULL")).isEqualTo(1);
                assertThat(scalar(st, "SELECT total_points FROM exams WHERE id = " + splitV3)).isEqualTo(0);

                // 8. No exam_version_id columns remain anywhere.
                assertThat(count(st, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE column_name = 'exam_version_id'")).isZero();
            }
        }
    }

    private static String script(String name) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of("docs/runbooks/sql", name));
    }

    private static Map<String, String> evidence(Statement st, String name) throws Exception {
        Map<String, String> rows = new java.util.TreeMap<>();
        try (ResultSet rs = st.executeQuery(script(name))) {
            while (rs.next()) {
                assertThat(rows.put(rs.getString(1) + ":" + rs.getLong(2),
                        rs.getString(3) + ":" + rs.getString(4))).isNull();
            }
        }
        return rows;
    }

    private static String examVersion(long schoolId, long examId, int number, String status, int duration, long userId) {
        return "INSERT INTO exam_versions (school_id, exam_id, version_number, title, duration_minutes, "
                + "total_points, status, published_at, created_by) VALUES (" + schoolId + "," + examId + "," + number
                + ",'Split v" + number + "'," + duration + "," + ("PUBLISHED".equals(status) ? 1 : 0) + ",'" + status + "',"
                + ("PUBLISHED".equals(status) ? "now()" : "NULL") + "," + userId + ")";
    }

    private static long versionOf(Statement st, long questionId) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT id FROM question_versions WHERE question_id = " + questionId)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long insert(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql + " RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long count(Statement st, String sql) throws Exception {
        return scalar(st, sql);
    }
}
