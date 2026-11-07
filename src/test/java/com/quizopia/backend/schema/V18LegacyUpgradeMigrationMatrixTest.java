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
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review M1-03 / Part III §20 migration matrix: upgrade a PHASE-1 database
 * (migrated exactly to V17, carrying legacy users/roles/workspaces) to the
 * M1 head and assert that (a) the new columns arrive with the correct trust
 * semantics — legacy login continuity preserved, email proof NOT fabricated —
 * and (b) no historical data is lost. Runs in a throwaway DATABASE of the
 * Testcontainers PostgreSQL (mirroring the staged-flyway pattern), leaving
 * the shared application database untouched.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V18LegacyUpgradeMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    @Test
    void phase1DatabaseUpgradesToM1HeadPreservingLegacySemantics() throws Exception {
        String dbName = "matrix_" + System.nanoTime();
        try {
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE " + dbName);
            }
            String url = pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            // Phase 1 endpoint state: only V1–V17 applied.
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("17")
                    .load()
                    .migrate();

            // Phase-1 legacy user with a role and a personal workspace.
            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                st.execute("""
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           token_version, failed_login_attempts, password_changed_at)
                        VALUES ('matrix_legacy', 'matrix@legacy.example.com', 'x', 'Matrix Legacy',
                                'ACTIVE', 0, 0, now())
                        """);
                st.execute("""
                        INSERT INTO user_roles (user_id, role_id, assigned_at)
                        SELECT u.id, r.id, now() FROM users u, roles r
                        WHERE u.username = 'matrix_legacy' AND r.code = 'TEACHER'
                        """);
                st.execute("""
                        INSERT INTO workspaces (name, workspace_type, owner_user_id, created_at, updated_at)
                        SELECT 'Matrix Personal', 'PERSONAL', u.id, now(), now()
                        FROM users u WHERE u.username = 'matrix_legacy'
                        """);
            }

            // Phase 1 DB → M1 head (V18+).
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                // Trust semantics: login continuity WITHOUT fabricated proof.
                try (ResultSet rs = st.executeQuery(
                        "SELECT login_requires_email_verification, email_verified_at"
                                + " FROM users WHERE username = 'matrix_legacy'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean("login_requires_email_verification"))
                            .as("legacy login compatibility flag must be FALSE")
                            .isFalse();
                    assertThat(rs.getObject("email_verified_at", OffsetDateTime.class))
                            .as("legacy email proof must NOT be grandfathered")
                            .isNull();
                }

                // Historical data preserved.
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id"
                                + " JOIN roles r ON r.id = ur.role_id"
                                + " WHERE u.username = 'matrix_legacy' AND r.code = 'TEACHER'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                // V25 (user-centric contract) drops the workspace era; the
                // legacy workspace row is gone WITH its table, and the user
                // keeps working under the STUDENT/TEACHER role model.
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'workspaces'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).as("workspaces must be gone after V25").isZero();
                }
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM users u JOIN user_roles ur ON ur.user_id = u.id"
                                + " JOIN roles r ON r.id = ur.role_id"
                                + " WHERE u.username = 'matrix_legacy' AND r.code IN ('STUDENT','TEACHER')")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }

                // New M1 tables exist on the upgraded database.
                for (String table : new String[]{
                        "email_verification_challenges", "external_identities",
                        "oauth_login_codes", "oauth_link_requests"}) {
                    try (ResultSet rs = st.executeQuery(
                            "SELECT count(*) FROM information_schema.tables"
                                    + " WHERE table_schema = 'public' AND table_name = '" + table + "'")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).as("table %s", table).isEqualTo(1);
                    }
                }
            }
        } finally {
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE " + dbName + " WITH (FORCE)");
            }
        }
    }
}
