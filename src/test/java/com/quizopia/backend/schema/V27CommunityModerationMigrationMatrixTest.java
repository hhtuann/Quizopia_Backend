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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/**
 * V27 community-moderation migration matrix (ADR-006 §3 V27, issue
 * #42), following the staged-flyway pattern of
 * {@code V26CommunityFoundationMigrationMatrixTest}:
 *
 * <ul>
 *   <li><b>FRESH</b> — an empty database migrates V1..V27 and ends with
 *       the reports + audit tables, the CHECK-constrained enums, the
 *       partial unique OPEN index and the audit indexes.</li>
 *   <li><b>UPGRADE</b> — a database staged at V26 (with live
 *       publications) migrates to V27 with an identical end state;
 *       V26 rows are untouched.</li>
 *   <li><b>SEMANTICS (both legs)</b> — the partial unique index allows
 *       at most one OPEN report per (reporter, publication) while a
 *       handled report never blocks a new OPEN one; the CHECK
 *       constraints reject out-of-enum reason codes, statuses and
 *       actions; audit rows require a reason.</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V27CommunityModerationMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    // ==================================================================
    // LEG 1 — FRESH: empty database → V27
    // ==================================================================
    @Test
    void freshDatabaseMigratesToV27WithModerationTables() throws Exception {
        String dbName = createDatabase("v27mx_fresh");
        try {
            String url = urlFor(dbName);
            Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(
                    url, pg.getUsername(), pg.getPassword());
                 Statement st = c.createStatement()) {
                assertSchema(st);
                assertSemantics(st);
                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                        .as("migration chain passed through V27 (head is now V32)")
                        .containsExactly("32");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // LEG 2 — UPGRADE: V26 with live community data → V27
    // ==================================================================
    @Test
    void stagedV26WithLivePublicationsUpgradesToV27() throws Exception {
        String dbName = createDatabase("v27mx_up");
        try {
            String url = urlFor(dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("26")
                    .load()
                    .migrate();

            long publicationId;
            long reporterId;
            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                long teacherId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v27up%s', 'v27up%s@t.example.com', 'h', 'UP', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(System.nanoTime(), System.nanoTime()));
                reporterId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v27ur%s', 'v27ur%s@t.example.com', 'h', 'UP', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(System.nanoTime(), System.nanoTime()));
                long examId = insert(st, """
                        INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                        VALUES (%d, 'V27E%s', 'Leg-2 exam', 'PUBLISHED', now(), 1)
                        """.formatted(teacherId, System.nanoTime()));
                publicationId = insert(st, """
                        INSERT INTO community_publications (exam_id, publisher_user_id, title,
                                                            status, moderation_state)
                        VALUES (%d, %d, 'Leg-2 publication', 'PUBLISHED', 'VISIBLE')
                        """.formatted(examId, teacherId));
            }

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                assertSchema(st);
                assertSemantics(st);

                // V26 data untouched by the upgrade.
                assertThat(count(st, """
                        SELECT count(*) FROM community_publications WHERE id = %d AND status = 'PUBLISHED'
                        """.formatted(publicationId)))
                        .as("pre-existing publication survives V27 unchanged")
                        .isEqualTo(1);

                // Cross-version flow: a V26-era publication receives
                // reports/audit rows under the V27 schema.
                insert(st, """
                        INSERT INTO community_reports (publication_id, reporter_user_id, reason_code, details)
                        VALUES (%d, %d, 'SPAM', 'legacy-leg report')
                        """.formatted(publicationId, reporterId));
                insert(st, """
                        INSERT INTO community_moderation_actions
                            (actor_user_id, publication_id, action, from_moderation_state,
                             to_moderation_state, reason)
                        VALUES (%d, %d, 'HIDE', 'VISIBLE', 'HIDDEN', 'legacy-leg hide')
                        """.formatted(reporterId, publicationId));
                assertThat(count(st, """
                        SELECT count(*) FROM community_moderation_actions
                        WHERE publication_id = %d AND action = 'HIDE'
                        """.formatted(publicationId)))
                        .isEqualTo(1);

                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                        .as("migration chain passed through V27 (head is now V32)")
                        .containsExactly("32");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // shared schema assertions (leg-agnostic)
    // ==================================================================

    private static void assertSchema(Statement st) throws SQLException {
        assertThat(rows(st, """
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('community_reports', 'community_moderation_actions')
                        ORDER BY table_name
                        """))
                .as("both V27 tables exist")
                .containsExactly("community_moderation_actions", "community_reports");

        assertThat(rows(st, """
                        SELECT indexname FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname IN ('uk_community_reports_open_per_reporter',
                                            'idx_community_reports_status_created',
                                            'idx_community_reports_publication',
                                            'idx_community_moderation_actions_publication',
                                            'idx_community_moderation_actions_report')
                        ORDER BY indexname
                        """))
                .as("partial unique + queue/audit indexes exist")
                .containsExactly(
                        "idx_community_moderation_actions_publication",
                        "idx_community_moderation_actions_report",
                        "idx_community_reports_publication",
                        "idx_community_reports_status_created",
                        "uk_community_reports_open_per_reporter");

        // The dedupe index is genuinely PARTIAL (predicate on OPEN).
        assertThat(rows(st, """
                        SELECT pg_get_expr(idx.indpred, idx.indrelid)
                        FROM pg_index idx
                        JOIN pg_class cls ON cls.oid = idx.indexrelid
                        WHERE cls.relname = 'uk_community_reports_open_per_reporter'
                        """))
                .first()
                .asString()
                .contains("OPEN");
    }

    /** Enum CHECKs + the one-OPEN-per-reporter invariant, both legs. */
    private static void assertSemantics(Statement st) throws SQLException {
        long ownerId = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v27s%s', 'v27s%s@t.example.com', 'h', 'S', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        long reporter = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v27r%s', 'v27r%s@t.example.com', 'h', 'R', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        long examId = insert(st, """
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'V27S%s', 'Semantics exam', 'PUBLISHED', now(), 1)
                """.formatted(ownerId, System.nanoTime()));
        long publicationId = insert(st, """
                INSERT INTO community_publications (exam_id, publisher_user_id, title,
                                                    status, moderation_state)
                VALUES (%d, %d, 'Semantics publication', 'PUBLISHED', 'VISIBLE')
                """.formatted(examId, ownerId));

        // One OPEN report per (reporter, publication)…
        insert(st, """
                INSERT INTO community_reports (publication_id, reporter_user_id, reason_code)
                VALUES (%d, %d, 'SPAM')
                """.formatted(publicationId, reporter));
        assertThatThrownBy(() -> insert(st, """
                        INSERT INTO community_reports (publication_id, reporter_user_id, reason_code)
                        VALUES (%d, %d, 'OTHER')
                        """.formatted(publicationId, reporter)))
                .as("partial unique index rejects a second OPEN report")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_community_reports_open_per_reporter");

        // …but a HANDLED report never blocks a new OPEN one.
        st.execute("""
                UPDATE community_reports SET status = 'DISMISSED' WHERE publication_id = %d
                """.formatted(publicationId));
        insert(st, """
                INSERT INTO community_reports (publication_id, reporter_user_id, reason_code)
                VALUES (%d, %d, 'MISLEADING')
                """.formatted(publicationId, reporter));

        // CHECK constraints: reason_code / status / action enums.
        assertThatThrownBy(() -> insert(st, """
                        INSERT INTO community_reports (publication_id, reporter_user_id, reason_code)
                        VALUES (%d, %d, 'NUISANCE')
                        """.formatted(publicationId, reporter)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_community_reports_reason_code");
        assertThatThrownBy(() -> insert(st, """
                        INSERT INTO community_reports (publication_id, reporter_user_id,
                                                      reason_code, status)
                        VALUES (%d, %d, 'SPAM', 'REOPENED')
                        """.formatted(publicationId, reporter)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_community_reports_status");
        assertThatThrownBy(() -> insert(st, """
                        INSERT INTO community_moderation_actions
                            (actor_user_id, publication_id, action, reason)
                        VALUES (%d, %d, 'BAN', 'x')
                        """.formatted(ownerId, publicationId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_community_moderation_actions_action");
        // reason NOT NULL on the audit trail.
        assertThatThrownBy(() -> insert(st, """
                        INSERT INTO community_moderation_actions
                            (actor_user_id, publication_id, action, reason)
                        VALUES (%d, %d, 'HIDE', NULL)
                        """.formatted(ownerId, publicationId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("reason");
    }

    // ==================================================================
    // staged-flyway helpers (V24/V26 matrix pattern)
    // ==================================================================

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

    private static long insert(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql + " RETURNING id")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
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
