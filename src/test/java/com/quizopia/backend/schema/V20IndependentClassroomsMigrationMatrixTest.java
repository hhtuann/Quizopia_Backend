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
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Identity-M3 migration matrix (System issue #15): a Phase-1 database
 * (V1–V17) with legacy school classrooms upgrades to V20 preserving every
 * legacy row and constraint semantics, then enforces the new
 * ownership-mode CHECK and roster unique rules.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V20IndependentClassroomsMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    @Test
    void phase1DatabaseUpgradesToV20PreservingLegacyClassrooms() throws Exception {
        String dbName = "m3matrix_" + System.nanoTime();
        try {
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE " + dbName);
            }
            String url = pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            // ---- Phase 1 endpoint state: only V1–V17 applied ----
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("17")
                    .load()
                    .migrate();

            // Legacy school + teacher + classroom.
            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                st.execute("""
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           token_version, failed_login_attempts, password_changed_at)
                        VALUES ('m3_legacy', 'm3@legacy.example.com', 'x', 'M3 Legacy',
                                'ACTIVE', 0, 0, now())
                        """);
                st.execute("""
                        INSERT INTO user_roles (user_id, role_id, assigned_at)
                        SELECT u.id, r.id, now() FROM users u, roles r
                        WHERE u.username = 'm3_legacy' AND r.code = 'TEACHER'
                        """);
                st.execute("""
                        INSERT INTO schools (code, name)
                        VALUES ('M3SCH', 'M3 School')
                        """);
                st.execute("""
                        INSERT INTO teacher_profiles (user_id, school_id, teacher_code)
                        SELECT u.id, s.id, 'M3TC1' FROM users u, schools s
                        WHERE u.username = 'm3_legacy' AND s.code = 'M3SCH'
                        """);
                st.execute("""
                        INSERT INTO organizations (legacy_school_id, code, name)
                        SELECT s.id, 'M3ORG', 'M3 Organization' FROM schools s
                        WHERE s.code = 'M3SCH'
                        """);
                st.execute("""
                        INSERT INTO workspaces (name, workspace_type, organization_id, created_at, updated_at)
                        SELECT 'M3 Org WS', 'ORGANIZATION', o.id, now(), now()
                        FROM organizations o WHERE o.code = 'M3ORG'
                        """);
                st.execute("""
                        INSERT INTO classrooms (school_id, owner_teacher_id, workspace_id, code, name)
                        SELECT s.id, tp.id, w.id, 'M3C1', 'Legacy Class'
                        FROM schools s
                        JOIN teacher_profiles tp ON tp.school_id = s.id
                        JOIN users u ON u.id = tp.user_id AND u.username = 'm3_legacy'
                        JOIN workspaces w ON w.name = 'M3 Org WS'
                        WHERE s.code = 'M3SCH'
                        """);
                // Legacy membership (user-based, V12+) tied to the classroom.
                st.execute("""
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           token_version, failed_login_attempts, password_changed_at)
                        VALUES ('m3_member', 'm3member@legacy.example.com', 'x', 'M3 Member',
                                'ACTIVE', 0, 0, now())
                        """);
                st.execute("""
                        INSERT INTO classroom_members (classroom_id, user_id, school_id, student_profile_id)
                        SELECT c.id, u.id, c.school_id, NULL
                        FROM classrooms c, users u
                        WHERE c.code = 'M3C1' AND u.username = 'm3_member'
                        """);
            }

            // ---- Phase 1 DB → Identity-M3 head (V18–V20) ----
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                // Legacy classroom preserved with backfilled owner_user_id AND
                // its org workspace unchanged (review M3-QA).
                try (ResultSet rs = st.executeQuery("""
                        SELECT c.school_id, c.owner_teacher_id, c.owner_user_id, c.code,
                               c.workspace_id,
                               (SELECT w.name FROM workspaces w WHERE w.id = c.workspace_id) ws
                        FROM classrooms c
                        """)) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject("school_id")).isNotNull();
                    assertThat(rs.getObject("owner_teacher_id")).isNotNull();
                    assertThat(rs.getObject("owner_user_id")).as("owner_user_id backfilled").isNotNull();
                    assertThat(rs.getString("code")).isEqualTo("M3C1");
                    assertThat(rs.getString("ws")).as("legacy classroom stays in ORG workspace")
                            .isEqualTo("M3 Org WS");
                    assertThat(rs.next()).as("exactly one legacy classroom").isFalse();
                }

                // Legacy membership preserved (attempt-ownership chain intact).
                try (ResultSet rs = st.executeQuery("""
                        SELECT count(*) FROM classroom_members cm
                        JOIN classrooms c ON c.id = cm.classroom_id
                        JOIN users u ON u.id = cm.user_id
                        WHERE c.code = 'M3C1' AND u.username = 'm3_member'
                        """)) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }

                // V20 schema objects exist.
                try (ResultSet rs = st.executeQuery("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name = 'classroom_roster_entries'
                        """)) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (ResultSet rs = st.executeQuery("""
                        SELECT count(*) FROM pg_indexes
                        WHERE indexname = 'uk_roster_pending_classroom_email'
                        """)) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }

                // Ownership-mode CHECK: fully independent row OK. The user's
                // personal workspace is provisioned by V18-era provisioning —
                // for the matrix test, create it directly.
                st.execute("""
                        INSERT INTO workspaces (name, workspace_type, owner_user_id, created_at, updated_at)
                        SELECT 'M3 Personal', 'PERSONAL', u.id, now(), now()
                        FROM users u WHERE u.username = 'm3_legacy'
                        """);
                st.execute("""
                        INSERT INTO classrooms (owner_user_id, workspace_id, code, name)
                        SELECT u.id, w.id, 'IND1', 'Independent Class'
                        FROM users u JOIN workspaces w ON w.owner_user_id = u.id
                        WHERE u.username = 'm3_legacy' AND w.workspace_type = 'PERSONAL'
                        """);

                // Mixed ownership (school without teacher) REJECTED.
                assertThatThrownBy(() -> st.execute("""
                        INSERT INTO classrooms (school_id, owner_user_id, workspace_id, code, name)
                        VALUES ((SELECT id FROM schools WHERE code='M3SCH'),
                                (SELECT id FROM users WHERE username='m3_legacy'),
                                (SELECT id FROM workspaces WHERE name='M3 Org WS'),
                                'BAD1', 'Bad Mixed')
                        """)).hasMessageContaining("chk_classrooms_ownership_mode");

                // Partial unique PENDING roster rule.
                st.execute("""
                        INSERT INTO classroom_roster_entries
                            (classroom_id, email_normalized, status, full_name, created_by_user_id)
                        VALUES ((SELECT id FROM classrooms WHERE code='IND1'),
                                'kid@example.com', 'PENDING', 'Kid One',
                                (SELECT id FROM users WHERE username='m3_legacy'))
                        """);
                assertThatThrownBy(() -> st.execute("""
                        INSERT INTO classroom_roster_entries
                            (classroom_id, email_normalized, status, full_name, created_by_user_id)
                        VALUES ((SELECT id FROM classrooms WHERE code='IND1'),
                                'kid@example.com', 'PENDING', 'Kid Dup',
                                (SELECT id FROM users WHERE username='m3_legacy'))
                        """)).hasMessageContaining("uk_roster_pending_classroom_email");
            }
        } finally {
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE " + dbName + " WITH (FORCE)");
            }
        }
    }
}
