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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User-centric cutover migration matrix (review F1): three legs proving the
 * V24 (expand/backfill) + V25 (contract) pair end to end on throwaway
 * databases of the Testcontainers PostgreSQL (staged-flyway pattern).
 *
 * <ul>
 *   <li><b>UPGRADE</b> — a database staged at V23 with representative legacy
 *       data (two schools with same-code grade levels/subjects/purposes, an
 *       ACADEMIC_ADMIN-only user, a SYSTEM_ADMIN-only user, a TEACHER, a
 *       STUDENT, teacher_profile-era banks/exams/classrooms with workspace
 *       columns, sessions, attempts, rosters and workspace memberships)
 *       migrates to V25 with the role model consolidated to
 *       STUDENT/TEACHER, the catalog deduped with references remapped, every
 *       content row preserved, and the workspace/org/school/profile era gone.</li>
 *   <li><b>AMBIGUITY STOP</b> — the same user owning the same question-bank
 *       code in two workspaces (legal pre-V24 uniqueness) FAILS V24 with the
 *       explicit ambiguity exception; the transaction rolls back and V25 is
 *       never applied.</li>
 *   <li><b>FRESH</b> — an empty database migrates V1..V25 in one chain and
 *       ends with the two-role model, the seeded global catalog and no
 *       legacy tables.</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V24UserCentricMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    private static final String[] LEGACY_TABLES = {
            "organizations", "workspaces", "workspace_memberships",
            "schools", "teacher_profiles", "student_profiles",
            "exam_versions", "legacy_exam_content_map"};

    // ==================================================================
    // LEG 1 — UPGRADE: V23 + legacy data → V24 + V25
    // ==================================================================
    @Test
    void legacyV23DatabaseUpgradesToUserCentricContractPreservingContent() throws Exception {
        String dbName = createDatabase("v24mx_up");
        try {
            String url = urlFor(dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            // ---- Stage at V23 (the last pre-cutover state). ----
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("23")
                    .load()
                    .migrate();
            insertLegacyMatrixData(url, user, pass);

            // ---- V24 + V25 (no target → latest). ----
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {

                // Role model: only STUDENT/TEACHER remain, correctly converted
                // (V26 later adds the dormant COMMUNITY_MODERATOR).
                assertThat(rows(st, "SELECT code FROM roles ORDER BY code"))
                        .as("roles are consolidated to STUDENT/TEACHER")
                        .containsExactly("COMMUNITY_MODERATOR", "STUDENT", "TEACHER");
                assertThat(rows(st, """
                        SELECT u.username || '=' || string_agg(r.code, ',' ORDER BY r.code)
                        FROM users u
                        JOIN user_roles ur ON ur.user_id = u.id
                        JOIN roles r ON r.id = ur.role_id
                        GROUP BY u.username ORDER BY u.username
                        """))
                        .as("ACADEMIC_ADMIN-only→TEACHER, SYSTEM_ADMIN-only→STUDENT, rest unchanged")
                        .containsExactly(
                                "mx_academic=TEACHER",
                                "mx_student=STUDENT",
                                "mx_system=STUDENT",
                                "mx_teacher=TEACHER",
                                "mx_teacher2=TEACHER");
                assertThat(count(st, """
                        SELECT count(*) FROM users u WHERE NOT EXISTS (
                            SELECT 1 FROM user_roles ur
                            JOIN roles r ON r.id = ur.role_id
                            WHERE ur.user_id = u.id AND r.code IN ('STUDENT', 'TEACHER'))
                        """))
                        .as("no user is left roleless")
                        .isZero();

                // Catalog dedupe: same-code rows across schools collapsed.
                assertThat(rows(st, "SELECT code FROM grade_levels ORDER BY code"))
                        .as("grade levels deduped by code")
                        .containsExactly("G10", "G12");
                assertThat(count(st, "SELECT count(*) FROM subjects")).isEqualTo(3);
                assertThat(count(st, "SELECT count(*) FROM subjects WHERE code = 'MATH'"))
                        .as("duplicate cross-school MATH subject collapsed to one")
                        .isEqualTo(1);
                assertThat(rows(st, "SELECT code FROM exam_purposes ORDER BY code"))
                        .as("purposes deduped by code")
                        .containsExactly("FINAL", "MIDTERM", "QUIZ");

                // Reference remap: school-2 bank/exam now point at the surviving rows.
                assertThat(rows(st, """
                        SELECT qb.code || '=' || s.code || '@' || gl.code
                        FROM question_banks qb
                        JOIN subjects s ON s.id = qb.subject_id
                        JOIN grade_levels gl ON gl.id = s.grade_level_id
                        ORDER BY qb.code
                        """))
                        .as("bank subject references remapped to the canonical subject")
                        .containsExactly("MX-BANK-A=MATH@G10", "MX-BANK-B=MATH@G10");
                assertThat(rows(st, """
                        SELECT e.code || '=' || s.code || '/' || p.code
                        FROM exams e
                        JOIN subjects s ON s.id = e.subject_id
                        JOIN exam_purposes p ON p.id = e.purpose_id
                        ORDER BY e.code
                        """))
                        .as("exam subject/purpose references remapped to the canonical rows")
                        .containsExactly("MX-EXAM-A=MATH/MIDTERM", "MX-EXAM-B=MATH/MIDTERM");

                // Content counts preserved through the cutover.
                assertThat(count(st, "SELECT count(*) FROM question_banks")).isEqualTo(2);
                assertThat(count(st, "SELECT count(*) FROM exams")).isEqualTo(2);
                assertThat(count(st, "SELECT count(*) FROM exam_sessions")).isEqualTo(2);
                assertThat(count(st, "SELECT count(*) FROM attempts")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM classrooms")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM classroom_members")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM classroom_roster_entries")).isEqualTo(2);
                assertThat(count(st, "SELECT count(*) FROM exam_session_participants")).isEqualTo(1);

                // Ownership chains still resolve user → content.
                assertThat(rows(st, """
                        SELECT u.username || '@' || es.code
                        FROM attempts a
                        JOIN users u ON u.id = a.user_id
                        JOIN exam_sessions es ON es.id = a.exam_session_id
                        """))
                        .containsExactly("mx_student@MX-SES-A");
                assertThat(rows(st, """
                        SELECT c.code || '=' || (SELECT username FROM users u WHERE u.id = c.owner_user_id)
                        FROM classrooms c
                        """))
                        .containsExactly("MX-CLS-A=mx_teacher");
                assertThat(rows(st, """
                        SELECT u.username FROM classroom_members cm JOIN users u ON u.id = cm.user_id
                        """))
                        .containsExactly("mx_student");
                assertThat(rows(st, "SELECT status FROM classroom_roster_entries ORDER BY status"))
                        .containsExactly("LINKED", "PENDING");

                // exam_session_classes preserved with plain (non-composite) FKs.
                assertThat(count(st, "SELECT count(*) FROM exam_session_classes")).isEqualTo(1);
                assertThat(count(st, """
                        SELECT count(*) FROM exam_session_classes esc
                        JOIN exam_sessions es ON es.id = esc.exam_session_id
                        JOIN classrooms c ON c.id = esc.classroom_id
                        WHERE es.code = 'MX-SES-A' AND c.code = 'MX-CLS-A'
                        """))
                        .as("the staged session-classroom link survives")
                        .isEqualTo(1);
                assertThat(count(st, """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN ('fk_esc_session', 'fk_esc_classroom')
                        """))
                        .as("plain session/classroom FKs exist after the composite swap")
                        .isEqualTo(2);

                // Workspace/org/school/profile era is GONE.
                for (String table : LEGACY_TABLES) {
                    assertThat(count(st, """
                            SELECT count(*) FROM information_schema.tables
                            WHERE table_schema = 'public' AND table_name = '%s'
                            """.formatted(table)))
                            .as("legacy table %s must be gone", table)
                            .isZero();
                }
                assertThat(count(st, """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND column_name IN ('workspace_id', 'owner_teacher_id', 'student_profile_id')
                        """))
                        .as("workspace/teacher/profile columns gone from every table")
                        .isZero();
                assertThat(count(st, """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public' AND column_name = 'school_id'
                          AND table_name IN ('question_banks', 'exams', 'exam_sessions', 'attempts',
                                             'exam_session_participants', 'classrooms', 'classroom_members',
                                             'exam_session_classes', 'subjects', 'grade_levels', 'exam_purposes')
                        """))
                        .as("school_id gone from content/catalog tables")
                        .isZero();

                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                        .as("migration chain passed through V24/V25 (head is now V29)")
                        .containsExactly("30");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // LEG 2 — AMBIGUITY STOP: duplicate creator+code across workspaces
    // ==================================================================
    @Test
    void ambiguousDuplicateBankCodeStopsV24BeforeAnyDestructiveStep() throws Exception {
        String dbName = createDatabase("v24mx_amb");
        try {
            String url = urlFor(dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("23")
                    .load()
                    .migrate();

            // One user owning the SAME bank code in TWO workspaces — legal
            // under the V23 (workspace, creator, code) uniqueness, ambiguous
            // under the target (creator, code) uniqueness.
            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                st.execute("""
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           token_version, failed_login_attempts, password_changed_at)
                        VALUES ('mx_amb', 'mx.amb@v24.example.com', 'x', 'MX Ambiguous',
                                'ACTIVE', 0, 0, now())
                        """);
                st.execute("""
                        INSERT INTO user_roles (user_id, role_id, assigned_at)
                        SELECT u.id, r.id, now() FROM users u, roles r
                        WHERE u.username = 'mx_amb' AND r.code = 'TEACHER'
                        """);
                st.execute("INSERT INTO organizations (code, name) VALUES ('AMBORG', 'Ambiguity Org')");
                st.execute("""
                        INSERT INTO workspaces (workspace_type, name, owner_user_id)
                        SELECT 'PERSONAL', 'Amb Personal', u.id FROM users u WHERE u.username = 'mx_amb'
                        """);
                st.execute("""
                        INSERT INTO workspaces (workspace_type, name, organization_id)
                        SELECT 'ORGANIZATION', 'Amb Org WS', o.id FROM organizations o WHERE o.code = 'AMBORG'
                        """);
                st.execute("""
                        INSERT INTO question_banks (workspace_id, created_by_user_id, code, name)
                        SELECT w.id, u.id, 'AMBIG', 'Ambiguity Bank Personal'
                        FROM workspaces w, users u
                        WHERE w.workspace_type = 'PERSONAL' AND u.username = 'mx_amb'
                        """);
                st.execute("""
                        INSERT INTO question_banks (workspace_id, created_by_user_id, code, name)
                        SELECT w.id, u.id, 'AMBIG', 'Ambiguity Bank Org'
                        FROM workspaces w, users u
                        WHERE w.workspace_type = 'ORGANIZATION' AND u.username = 'mx_amb'
                        """);
            }

            // V24 must FAIL with the explicit ambiguity exception.
            Throwable failure = null;
            try {
                Flyway.configure().dataSource(url, user, pass)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
            } catch (RuntimeException ex) {
                failure = ex;
            }
            assertThat(failure).as("V24 must stop on ambiguous ownership").isNotNull();
            StringBuilder chain = new StringBuilder();
            for (Throwable t = failure; t != null; t = t.getCause()) {
                chain.append(String.valueOf(t.getMessage())).append('\n');
            }
            assertThat(chain.toString())
                    .as("the ambiguity exception surfaces with its dedicated message")
                    .contains("duplicate question bank codes");

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                // The failed V24 rolled back completely (transactional DDL):
                // no V24 row in the history at all, last good version is 23,
                // and V25 was never applied.
                assertThat(count(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '24'"))
                        .as("failed V24 leaves no history row (fully rolled back)")
                        .isZero();
                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """)).containsExactly("23");
                assertThat(count(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '25'"))
                        .as("V25 not applied after the failed V24")
                        .isZero();

                // Nothing dropped, nothing reshaped: the whole migration
                // (including the RBAC and dedupe steps that run before the
                // ambiguity pre-check) rolled back with it.
                assertThat(count(st, """
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('workspaces', 'organizations', 'workspace_memberships',
                                             'schools', 'teacher_profiles', 'student_profiles')
                        """)).as("workspace/org/school tables still present").isEqualTo(6);
                assertThat(count(st, """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'subjects' AND column_name = 'school_id'
                        """)).as("catalog school scope still present").isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM roles WHERE code IN ('SYSTEM_ADMIN', 'ACADEMIC_ADMIN')"))
                        .as("admin roles still present (RBAC step rolled back)")
                        .isEqualTo(2);
                assertThat(count(st, "SELECT count(*) FROM question_banks"))
                        .as("both ambiguous banks intact")
                        .isEqualTo(2);
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // LEG 3 — FRESH: empty DB → V1..V25 in one chain
    // ==================================================================
    @Test
    void freshDatabaseMigratesV1toV25WithSeededCatalog() throws Exception {
        String dbName = createDatabase("v24mx_fresh");
        try {
            String url = urlFor(dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                assertThat(rows(st, "SELECT code FROM roles ORDER BY code"))
                        .as("fresh database ends with STUDENT/TEACHER plus the V26 dormant moderator")
                        .containsExactly("COMMUNITY_MODERATOR", "STUDENT", "TEACHER");
                assertThat(rows(st, "SELECT code FROM grade_levels ORDER BY code"))
                        .as("global grade levels seeded")
                        .containsExactly("G10", "G11", "G12");
                assertThat(count(st, "SELECT count(*) FROM subjects"))
                        .as("15 subjects per grade level")
                        .isEqualTo(45);
                assertThat(rows(st, "SELECT code FROM exam_purposes ORDER BY code"))
                        .as("global exam purposes seeded")
                        .containsExactly("FINAL", "MIDTERM", "PRACTICE", "QUIZ");
                for (String table : LEGACY_TABLES) {
                    assertThat(count(st, """
                            SELECT count(*) FROM information_schema.tables
                            WHERE table_schema = 'public' AND table_name = '%s'
                            """.formatted(table)))
                            .as("legacy table %s absent on a fresh database", table)
                            .isZero();
                }
                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                        .as("fresh migration reaches the current head (V29)")
                        .containsExactly("30");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // Fixtures & helpers
    // ==================================================================

    /**
     * Inserts representative V23 legacy data: two schools with same-code
     * grade levels/subjects/purposes, the four role-model personas, and a
     * teacher_profile-era content chain (banks/exams resolve workspace +
     * creator through the transitional triggers, exactly like raw fixtures
     * of that era).
     */
    private void insertLegacyMatrixData(String url, String user, String pass) throws SQLException {
        try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
             Statement st = c.createStatement()) {

            st.execute("""
                    INSERT INTO users (username, email, password_hash, display_name, status,
                                       token_version, failed_login_attempts, password_changed_at)
                    VALUES
                        ('mx_academic', 'mx.academic@v24.example.com', 'x', 'MX Academic', 'ACTIVE', 0, 0, now()),
                        ('mx_system',   'mx.system@v24.example.com',   'x', 'MX System',   'ACTIVE', 0, 0, now()),
                        ('mx_teacher',  'mx.teacher@v24.example.com',  'x', 'MX Teacher',  'ACTIVE', 0, 0, now()),
                        ('mx_teacher2', 'mx.teacher2@v24.example.com', 'x', 'MX Teacher2', 'ACTIVE', 0, 0, now()),
                        ('mx_student',  'mx.student@v24.example.com',  'x', 'MX Student',  'ACTIVE', 0, 0, now())
                    """);
            st.execute("""
                    INSERT INTO user_roles (user_id, role_id, assigned_at)
                    SELECT u.id, r.id, now() FROM users u, roles r
                    WHERE u.username = 'mx_academic' AND r.code = 'ACADEMIC_ADMIN'
                    """);
            st.execute("""
                    INSERT INTO user_roles (user_id, role_id, assigned_at)
                    SELECT u.id, r.id, now() FROM users u, roles r
                    WHERE u.username = 'mx_system' AND r.code = 'SYSTEM_ADMIN'
                    """);
            st.execute("""
                    INSERT INTO user_roles (user_id, role_id, assigned_at)
                    SELECT u.id, r.id, now() FROM users u, roles r
                    WHERE u.username = 'mx_teacher' AND r.code = 'TEACHER'
                    """);
            st.execute("""
                    INSERT INTO user_roles (user_id, role_id, assigned_at)
                    SELECT u.id, r.id, now() FROM users u, roles r
                    WHERE u.username = 'mx_teacher2' AND r.code = 'TEACHER'
                    """);
            st.execute("""
                    INSERT INTO user_roles (user_id, role_id, assigned_at)
                    SELECT u.id, r.id, now() FROM users u, roles r
                    WHERE u.username = 'mx_student' AND r.code = 'STUDENT'
                    """);

            st.execute("INSERT INTO schools (code, name) VALUES ('MXSCH1', 'Matrix School One')");
            st.execute("INSERT INTO schools (code, name) VALUES ('MXSCH2', 'Matrix School Two')");
            st.execute("""
                    INSERT INTO teacher_profiles (user_id, school_id, teacher_code)
                    SELECT u.id, s.id, 'MXTC1' FROM users u, schools s
                    WHERE u.username = 'mx_teacher' AND s.code = 'MXSCH1'
                    """);
            st.execute("""
                    INSERT INTO teacher_profiles (user_id, school_id, teacher_code)
                    SELECT u.id, s.id, 'MXTC2' FROM users u, schools s
                    WHERE u.username = 'mx_teacher2' AND s.code = 'MXSCH2'
                    """);
            st.execute("""
                    INSERT INTO student_profiles (user_id, school_id, student_code)
                    SELECT u.id, s.id, 'MXSC1' FROM users u, schools s
                    WHERE u.username = 'mx_student' AND s.code = 'MXSCH1'
                    """);

            // Same-code catalog rows in BOTH schools (dedupe fodder).
            st.execute("""
                    INSERT INTO grade_levels (school_id, code, name, sort_order)
                    SELECT s.id, v.code, v.name, v.sort_order
                    FROM schools s
                    CROSS JOIN (VALUES ('G10', 'Grade 10', 10), ('G12', 'Grade 12', 12))
                        AS v(code, name, sort_order)
                    """);
            st.execute("""
                    INSERT INTO subjects (school_id, grade_level_id, code, name)
                    SELECT gl.school_id, gl.id, v.code, v.name
                    FROM grade_levels gl
                    JOIN (VALUES ('G10', 'MATH', 'Mathematics'), ('G12', 'PHY', 'Physics'))
                        AS v(glcode, code, name) ON v.glcode = gl.code
                    WHERE gl.school_id = (SELECT id FROM schools WHERE code = 'MXSCH1')
                    """);
            st.execute("""
                    INSERT INTO subjects (school_id, grade_level_id, code, name)
                    SELECT gl.school_id, gl.id, v.code, v.name
                    FROM grade_levels gl
                    JOIN (VALUES ('G10', 'MATH', 'Mathematics'), ('G12', 'CHEM', 'Chemistry'))
                        AS v(glcode, code, name) ON v.glcode = gl.code
                    WHERE gl.school_id = (SELECT id FROM schools WHERE code = 'MXSCH2')
                    """);
            st.execute("""
                    INSERT INTO exam_purposes (school_id, code, title, position)
                    SELECT s.id, v.code, v.title, v.position
                    FROM schools s
                    JOIN (VALUES ('MIDTERM', 'Midterm', 1), ('FINAL', 'Final', 2))
                        AS v(code, title, position) ON s.code = 'MXSCH1'
                    """);
            st.execute("""
                    INSERT INTO exam_purposes (school_id, code, title, position)
                    SELECT s.id, v.code, v.title, v.position
                    FROM schools s
                    JOIN (VALUES ('MIDTERM', 'Midterm', 1), ('QUIZ', 'Quiz', 3))
                        AS v(code, title, position) ON s.code = 'MXSCH2'
                    """);

            // Teacher_profile-era content: workspace_id/created_by_user_id are
            // resolved by the transitional ownership triggers.
            st.execute("""
                    INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name)
                    SELECT s.id, sub.id, tp.id, 'MX-BANK-A', 'Matrix Bank One'
                    FROM schools s
                    JOIN subjects sub ON sub.school_id = s.id AND sub.code = 'MATH'
                    JOIN teacher_profiles tp ON tp.school_id = s.id
                        AND tp.user_id = (SELECT id FROM users WHERE username = 'mx_teacher')
                    WHERE s.code = 'MXSCH1'
                    """);
            st.execute("""
                    INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name)
                    SELECT s.id, sub.id, tp.id, 'MX-BANK-B', 'Matrix Bank Two'
                    FROM schools s
                    JOIN subjects sub ON sub.school_id = s.id AND sub.code = 'MATH'
                    JOIN teacher_profiles tp ON tp.school_id = s.id
                        AND tp.user_id = (SELECT id FROM users WHERE username = 'mx_teacher2')
                    WHERE s.code = 'MXSCH2'
                    """);
            st.execute("""
                    INSERT INTO exams (school_id, subject_id, owner_teacher_id, purpose_id, code, title)
                    SELECT s.id, sub.id, tp.id, p.id, 'MX-EXAM-A', 'Matrix Exam One'
                    FROM schools s
                    JOIN subjects sub ON sub.school_id = s.id AND sub.code = 'MATH'
                    JOIN exam_purposes p ON p.school_id = s.id AND p.code = 'MIDTERM'
                    JOIN teacher_profiles tp ON tp.school_id = s.id
                        AND tp.user_id = (SELECT id FROM users WHERE username = 'mx_teacher')
                    WHERE s.code = 'MXSCH1'
                    """);
            st.execute("""
                    INSERT INTO exams (school_id, subject_id, owner_teacher_id, purpose_id, code, title)
                    SELECT s.id, sub.id, tp.id, p.id, 'MX-EXAM-B', 'Matrix Exam Two'
                    FROM schools s
                    JOIN subjects sub ON sub.school_id = s.id AND sub.code = 'MATH'
                    JOIN exam_purposes p ON p.school_id = s.id AND p.code = 'MIDTERM'
                    JOIN teacher_profiles tp ON tp.school_id = s.id
                        AND tp.user_id = (SELECT id FROM users WHERE username = 'mx_teacher2')
                    WHERE s.code = 'MXSCH2'
                    """);

            // Post-redesign sessions (exam_id + duration since V14).
            st.execute("""
                    INSERT INTO exam_sessions (school_id, owner_teacher_id, exam_id, code, title,
                                               status, starts_at, ends_at, duration_minutes, created_by)
                    SELECT s.id, tp.id, e.id, 'MX-SES-A', 'Matrix Session One', 'SCHEDULED',
                           now(), now() + interval '2 hours', 30, e.created_by_user_id
                    FROM exams e
                    JOIN schools s ON s.id = e.school_id
                    JOIN teacher_profiles tp ON tp.school_id = s.id AND tp.user_id = e.created_by_user_id
                    WHERE e.code = 'MX-EXAM-A'
                    """);
            st.execute("""
                    INSERT INTO exam_sessions (school_id, owner_teacher_id, exam_id, code, title,
                                               status, starts_at, ends_at, duration_minutes, created_by)
                    SELECT s.id, tp.id, e.id, 'MX-SES-B', 'Matrix Session Two', 'SCHEDULED',
                           now(), now() + interval '2 hours', 45, e.created_by_user_id
                    FROM exams e
                    JOIN schools s ON s.id = e.school_id
                    JOIN teacher_profiles tp ON tp.school_id = s.id AND tp.user_id = e.created_by_user_id
                    WHERE e.code = 'MX-EXAM-B'
                    """);

            // Classroom (school era + backfilled user ownership since V20),
            // membership, roster, attempt, participant, session-classroom link.
            st.execute("""
                    INSERT INTO classrooms (school_id, owner_teacher_id, owner_user_id, code, name)
                    SELECT s.id, tp.id, u.id, 'MX-CLS-A', 'Matrix Class One'
                    FROM schools s
                    JOIN teacher_profiles tp ON tp.school_id = s.id
                        AND tp.user_id = (SELECT id FROM users WHERE username = 'mx_teacher')
                    JOIN users u ON u.username = 'mx_teacher'
                    WHERE s.code = 'MXSCH1'
                    """);
            st.execute("""
                    INSERT INTO classroom_members (classroom_id, user_id, school_id, student_profile_id)
                    SELECT c.id, u.id, c.school_id, sp.id
                    FROM classrooms c
                    JOIN users u ON u.username = 'mx_student'
                    JOIN student_profiles sp ON sp.user_id = u.id
                    WHERE c.code = 'MX-CLS-A'
                    """);
            st.execute("""
                    INSERT INTO classroom_roster_entries
                        (classroom_id, email_normalized, status, full_name, created_by_user_id)
                    SELECT c.id, 'mx.pending@v24.example.com', 'PENDING', 'Pending Kid',
                           (SELECT id FROM users WHERE username = 'mx_teacher')
                    FROM classrooms c WHERE c.code = 'MX-CLS-A'
                    """);
            st.execute("""
                    INSERT INTO classroom_roster_entries
                        (classroom_id, email_normalized, status, full_name, created_by_user_id,
                         user_id, linked_at)
                    SELECT c.id, 'mx.linked@v24.example.com', 'LINKED', 'Linked Kid',
                           (SELECT id FROM users WHERE username = 'mx_teacher'),
                           (SELECT id FROM users WHERE username = 'mx_student'), now()
                    FROM classrooms c WHERE c.code = 'MX-CLS-A'
                    """);
            st.execute("""
                    INSERT INTO exam_session_classes (exam_session_id, classroom_id, school_id)
                    SELECT es.id, c.id, es.school_id
                    FROM exam_sessions es, classrooms c
                    WHERE es.code = 'MX-SES-A' AND c.code = 'MX-CLS-A'
                    """);
            st.execute("""
                    INSERT INTO exam_session_participants
                        (school_id, exam_session_id, student_profile_id, user_id, added_by)
                    SELECT es.school_id, es.id, sp.id, u.id,
                           (SELECT id FROM users WHERE username = 'mx_teacher')
                    FROM exam_sessions es
                    JOIN users u ON u.username = 'mx_student'
                    JOIN student_profiles sp ON sp.user_id = u.id
                    WHERE es.code = 'MX-SES-A'
                    """);
            st.execute("""
                    INSERT INTO attempts (school_id, exam_session_id, student_profile_id, user_id,
                                          attempt_number, started_at, deadline_at)
                    SELECT es.school_id, es.id, sp.id, u.id, 1, now(), now() + interval '90 minutes'
                    FROM exam_sessions es
                    JOIN users u ON u.username = 'mx_student'
                    JOIN student_profiles sp ON sp.user_id = u.id
                    WHERE es.code = 'MX-SES-A'
                    """);

            // Workspace memberships in the lazily provisioned org workspace
            // (created by the ownership triggers above) — dropped WITH the
            // table in V25.
            st.execute("""
                    INSERT INTO workspace_memberships (workspace_id, user_id, role, status)
                    SELECT w.id, u.id,
                           CASE WHEN u.username = 'mx_teacher' THEN 'EDITOR' ELSE 'MEMBER' END,
                           'ACTIVE'
                    FROM workspaces w
                    JOIN organizations o ON o.id = w.organization_id
                    JOIN schools s ON s.id = o.legacy_school_id AND s.code = 'MXSCH1'
                    JOIN users u ON u.username IN ('mx_teacher', 'mx_student')
                    WHERE w.workspace_type = 'ORGANIZATION'
                    """);
        }
    }

    private String createDatabase(String prefix) throws SQLException {
        String dbName = prefix + "_" + System.nanoTime();
        try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
        }
        return dbName;
    }

    private String urlFor(String dbName) {
        return pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + dbName);
    }

    private void dropDatabase(String dbName) throws SQLException {
        try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE " + dbName + " WITH (FORCE)");
        }
    }

    private static int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
