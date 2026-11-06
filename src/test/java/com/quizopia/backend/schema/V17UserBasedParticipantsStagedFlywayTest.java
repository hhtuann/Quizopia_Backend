package com.quizopia.backend.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staged-migration proof for V17 (issue #4 P0-R8): legacy profile-based
 * participants migrate to user-based identity WITHOUT any change to row ids,
 * statuses, blockedAt or history; the (session, user) unique key is complete;
 * post-migration user-based rows (profile-less) are accepted.
 */
class V17UserBasedParticipantsStagedFlywayTest {

    @Test
    void legacyParticipantsMigrateWithIdsAndStatusPreserved() throws Exception {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withDatabaseName("v17_staged").withUsername("t").withPassword("t")) {
            pg.start();
            String url = pg.getJdbcUrl();
            String user = pg.getUsername();
            String pass = pg.getPassword();

            // Stage 1: schema through V16 (pre-user-based participants).
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("16").load().migrate();

            long participantId1;
            long participantId2;
            long schoolUser1;
            long schoolUser2;
            try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
                long u1 = ins(st, "INSERT INTO users (username, email, password_hash, display_name) VALUES ('v17a','v17a@t','h','A')");
                long u2 = ins(st, "INSERT INTO users (username, email, password_hash, display_name) VALUES ('v17b','v17b@t','h','B')");
                schoolUser1 = u1;
                schoolUser2 = u2;
                long school = ins(st, "INSERT INTO schools (code, name) VALUES ('V17-SCH','S')");
                long gl = ins(st, "INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
                long sub = ins(st, "INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'S','S')");
                long tp = ins(st, "INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u1 + "," + school + ",'TC')");
                long sp1 = ins(st, "INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u1 + "," + school + ",'SA')");
                long sp2 = ins(st, "INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u2 + "," + school + ",'SB')");
                long org = ins(st, "INSERT INTO organizations (legacy_school_id, code, name) VALUES (" + school + ",'V17-O','O')");
                long ws = ins(st, "INSERT INTO workspaces (workspace_type, name, organization_id) VALUES ('ORGANIZATION','O'," + org + ")");
                long bank = ins(st, "INSERT INTO question_banks (workspace_id, created_by_user_id, school_id, subject_id, owner_teacher_id, code, name) VALUES (" + ws + "," + u1 + "," + school + "," + sub + "," + tp + ",'B','B')");
                long q = ins(st, "INSERT INTO questions (question_bank_id, code, status, created_by) VALUES (" + bank + ",'q','ACTIVE'," + u1 + ")");
                long v = ins(st, "INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','q','MEDIUM',1,'{}'::jsonb," + u1 + ")");
                for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
                    st.execute("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','o'," + o[1] + "," + o[2] + ")");
                }
                long exam = ins(st, "INSERT INTO exams (workspace_id, created_by_user_id, school_id, subject_id, owner_teacher_id, code, title, status, published_at, total_points, instructions, duration_minutes, tf_matrix_scoring) VALUES (" + ws + "," + u1 + "," + school + "," + sub + "," + tp + ",'V17E','E','PUBLISHED',now(),1,'i',30,'{\"0\":0,\"1\":10,\"2\":25,\"3\":50,\"4\":100}'::jsonb)");
                long sec = ins(st, "INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
                long eq = ins(st, "INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + exam + "," + sec + "," + q + "," + v + ",'q','SINGLE_CHOICE','q?',1,0,'{}'::jsonb)");
                for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
                    st.execute("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'" + o[0] + "','o'," + o[1] + "," + o[2] + ")");
                }
                long session = ins(st, "INSERT INTO exam_sessions (exam_id, school_id, owner_teacher_id, code, title, status, starts_at, ends_at, created_by, visibility, max_attempts, duration_minutes, opened_at) VALUES (" + exam + "," + school + "," + tp + ",'V17S','S','OPEN',now()-interval '1 hour',now()+interval '2 hours'," + u1 + ",'PUBLIC',5,60,now())");

                // Two legacy participants: one ELIGIBLE, one BLOCKED.
                participantId1 = ins(st, "INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp1 + "," + u1 + ")");
                participantId2 = ins(st, "INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by, status, blocked_at) VALUES (" + school + "," + session + "," + sp2 + "," + u1 + ",'BLOCKED',now())");
            }

            // Stage 2: run V17.
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration").load().migrate();

            try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
                // IDs, statuses, blockedAt preserved; user_id backfilled.
                try (ResultSet rs = st.executeQuery("SELECT id, user_id, status, blocked_at IS NOT NULL AS was_blocked "
                        + "FROM exam_session_participants WHERE id IN (" + participantId1 + "," + participantId2 + ") ORDER BY id")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("id")).isEqualTo(participantId1);
                    assertThat(rs.getLong("user_id")).isEqualTo(schoolUser1);
                    assertThat(rs.getString("status")).isEqualTo("ELIGIBLE");
                    assertThat(rs.getBoolean("was_blocked")).isFalse();

                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("id")).isEqualTo(participantId2);
                    assertThat(rs.getLong("user_id")).isEqualTo(schoolUser2);
                    assertThat(rs.getString("status")).isEqualTo("BLOCKED");
                    assertThat(rs.getBoolean("was_blocked")).isTrue();
                    assertThat(rs.next()).isFalse();
                }

                // (session, user) uniqueness complete.
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM ("
                        + "SELECT exam_session_id, user_id, count(*) c FROM exam_session_participants "
                        + "GROUP BY exam_session_id, user_id HAVING count(*) > 1) d")) {
                    rs.next();
                    assertThat(rs.getLong(1)).isZero();
                }

                // Post-migration: user-based profile-less row insertable for a
                // PUBLIC participant (no student_profiles row exists for u3).
                long u3 = ins(st, "INSERT INTO users (username, email, password_hash, display_name) VALUES ('v17c','v17c@t','h','C')");
                long session = qLong(st, "SELECT id FROM exam_sessions WHERE code = 'V17S'");
                st.execute("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by, status) "
                        + "VALUES (" + u3 + "," + session + ",1,'ELIGIBLE')");
                // Duplicate (session, user) rejected by the new unique index.
                boolean dup = false;
                try {
                    st.execute("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by, status) "
                            + "VALUES (" + u3 + "," + session + ",1,'ELIGIBLE')");
                } catch (Exception expected) {
                    dup = true;
                }
                assertThat(dup).as("duplicate (session,user) participant must be rejected").isTrue();

                // Latest version is 17.
                try (ResultSet rs = st.executeQuery("SELECT version FROM flyway_schema_history WHERE success "
                        + "ORDER BY installed_rank DESC LIMIT 1")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("23");
                }
            }
        }
    }

    private static long ins(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql + " RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long qLong(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
