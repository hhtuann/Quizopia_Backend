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
 * V26 community-foundation migration matrix (ADR-006 §3, issue #40),
 * following the staged-flyway pattern of {@code V24UserCentricMigrationMatrixTest}:
 *
 * <ul>
 *   <li><b>FRESH</b> — an empty database migrates V1..V26 and ends with
 *       the deduplicated category taxonomy (15 unique codes — the V24
 *       catalog seeds all 15 subject codes per grade level, so the seed's
 *       DISTINCT ON dedup is exercised on this leg too), contiguous
 *       positions, the COMMUNITY_* RBAC seeds and the dormant
 *       COMMUNITY_MODERATOR role.</li>
 *   <li><b>UPGRADE</b> — a database staged at V25 with legacy catalog
 *       data (an extra grade level carrying a different-name duplicate
 *       of an existing code, plus an INACTIVE subject with a unique code)
 *       migrates to V26: the taxonomy keeps one row per distinct code
 *       with the deterministic lowest-id name, the INACTIVE subject is
 *       excluded, and the RBAC seeds are identical.</li>
 *   <li><b>IDEMPOTENT SEEDS</b> — re-running the V26 permission/role/
 *       grant seed statements against the migrated database inserts
 *       nothing (WHERE NOT EXISTS).</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V26CommunityFoundationMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    // ==================================================================
    // LEG 1 — FRESH: empty database → V26
    // ==================================================================
    @Test
    void freshDatabaseMigratesToV26WithSeededTaxonomyAndRbac() throws Exception {
        String dbName = createDatabase("v26mx_fresh");
        try {
            String url = urlFor(dbName);
            Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
                 Statement st = c.createStatement()) {

                assertTaxonomy(st);
                assertRbacSeeds(st);
                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                .as("migration chain passed through V26 (head is now V28)")
                .containsExactly("28");

                // The V24 catalog seeds every code per grade level — the
                // seed dedup ran on this leg too.
                assertThat(count(st, "SELECT count(*) FROM subjects")).isGreaterThan(15);
                assertThat(count(st, """
                        SELECT count(DISTINCT LOWER(code)) FROM subjects
                        """)).isEqualTo(15);
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // LEG 2 — UPGRADE: V25 + legacy catalog data → V26
    // ==================================================================
    @Test
    void stagedV25WithLegacySubjectsUpgradesToV26Deduplicated() throws Exception {
        String dbName = createDatabase("v26mx_up");
        try {
            String url = urlFor(dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            // ---- Stage at V25 (the last pre-community state). ----
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("25")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                // Legacy fodder: a fourth grade level whose TOAN row carries a
                // different name (deterministic tie-break must keep the
                // lowest-id V24 row's name), and an INACTIVE unique code that
                // must never become a category.
                st.execute("""
                        INSERT INTO grade_levels (code, name, sort_order)
                        VALUES ('G13', 'Grade 13', 13)
                        """);
                st.execute("""
                        INSERT INTO subjects (grade_level_id, code, name, status)
                        SELECT gl.id, 'TOAN', 'Legacy Per-Grade Math Name', 'ACTIVE'
                        FROM grade_levels gl WHERE gl.code = 'G13'
                        """);
                st.execute("""
                        INSERT INTO subjects (grade_level_id, code, name, status)
                        SELECT gl.id, 'ZOFF', 'Dormant Subject', 'INACTIVE'
                        FROM grade_levels gl WHERE gl.code = 'G13'
                        """);
            }

            // ---- V26 ----
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {

                assertTaxonomy(st);
                assertRbacSeeds(st);

                // Deterministic tie-break: TOAN kept the LOWEST subject id's
                // name (a V24 catalog row), not the legacy duplicate's name.
                assertThat(rows(st, """
                        SELECT title FROM community_categories WHERE code = 'toan'
                        """))
                        .as("duplicate-code tie-break keeps the lowest-id row's name")
                        .containsExactly("Mathematics");
                assertThat(count(st, """
                        SELECT count(*) FROM community_categories WHERE code = 'zoff'
                        """))
                        .as("INACTIVE subjects never seed categories")
                        .isZero();

                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                .as("migration chain passed through V26 (head is now V28)")
                .containsExactly("28");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // LEG 3 — IDEMPOTENT SEEDS: re-running the RBAC seed SQL adds nothing
    // ==================================================================
    @Test
    void rbacSeedStatementsAreIdempotentWhenReapplied() throws Exception {
        String dbName = createDatabase("v26mx_idem");
        try {
            String url = urlFor(dbName);
            Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
                 Statement st = c.createStatement()) {

                int permissionsBefore = count(st, "SELECT count(*) FROM permissions");
                int rolesBefore = count(st, "SELECT count(*) FROM roles");
                int grantsBefore = count(st, "SELECT count(*) FROM role_permissions");

                // The exact V26 seed statements, applied a second time.
                st.execute("""
                        INSERT INTO permissions (code, name, description)
                        SELECT v.code, v.name, v.description
                        FROM (VALUES
                            ('COMMUNITY_PUBLISH', 'Publish to Community', 'x'),
                            ('COMMUNITY_FORK', 'Fork Community Publication', 'x'),
                            ('COMMUNITY_REPORT', 'Report Community Publication', 'x'),
                            ('COMMUNITY_BOOKMARK', 'Bookmark Community Publication', 'x'),
                            ('COMMUNITY_MODERATION', 'Moderate Community', 'x')
                        ) AS v(code, name, description)
                        WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.code = v.code)
                        """);
                st.execute("""
                        INSERT INTO roles (code, name, description)
                        SELECT 'COMMUNITY_MODERATOR', 'Community Moderator', 'x'
                        WHERE NOT EXISTS (SELECT 1 FROM roles r WHERE r.code = 'COMMUNITY_MODERATOR')
                        """);
                st.execute("""
                        INSERT INTO role_permissions (role_id, permission_id)
                        SELECT r.id, p.id
                        FROM roles r
                        JOIN permissions p ON p.code IN ('COMMUNITY_PUBLISH', 'COMMUNITY_FORK',
                                                         'COMMUNITY_REPORT', 'COMMUNITY_BOOKMARK')
                        WHERE r.code = 'TEACHER'
                          AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                                          WHERE rp.role_id = r.id AND rp.permission_id = p.id)
                        """);

                assertThat(count(st, "SELECT count(*) FROM permissions")).isEqualTo(permissionsBefore);
                assertThat(count(st, "SELECT count(*) FROM roles")).isEqualTo(rolesBefore);
                assertThat(count(st, "SELECT count(*) FROM role_permissions")).isEqualTo(grantsBefore);
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // shared assertions
    // ==================================================================

    /** Leg-agnostic taxonomy assertion (ADR-006 §3): NOT a literal count. */
    private static void assertTaxonomy(Statement st) throws SQLException {
        assertThat(count(st, "SELECT count(*) FROM community_categories"))
                .as("one category per distinct code")
                .isEqualTo(count(st, "SELECT count(DISTINCT LOWER(code)) FROM community_categories"));
        // The V24 catalog carries exactly 15 distinct ACTIVE codes on both legs.
        assertThat(count(st, "SELECT count(*) FROM community_categories")).isEqualTo(15);
        assertThat(rows(st, """
                        SELECT code || '=' || position FROM community_categories ORDER BY position
                        """))
                .as("positions are contiguous 1..15 with the deduplicated codes")
                .containsExactly(
                        "anh=1", "cn=2", "dia=3", "gdcd=4", "gdqp=5",
                        "hoa=6", "ly=7", "mt=8", "nhac=9", "sinh=10",
                        "su=11", "td=12", "tin=13", "toan=14", "van=15");
    }

    private static void assertRbacSeeds(Statement st) throws SQLException {
        assertThat(rows(st, """
                        SELECT p.code FROM permissions p WHERE p.code LIKE 'COMMUNITY_%' ORDER BY p.code
                        """))
                .as("the five COMMUNITY_* permissions are seeded")
                .containsExactly("COMMUNITY_BOOKMARK", "COMMUNITY_FORK", "COMMUNITY_MODERATION",
                        "COMMUNITY_PUBLISH", "COMMUNITY_REPORT");
        assertThat(rows(st, """
                        SELECT r.code || '→' || p.code
                        FROM role_permissions rp
                        JOIN roles r ON r.id = rp.role_id
                        JOIN permissions p ON p.id = rp.permission_id
                        WHERE p.code LIKE 'COMMUNITY_%'
                        ORDER BY r.code, p.code
                        """))
                .as("grants: PUBLISH/FORK→TEACHER, REPORT/BOOKMARK→STUDENT+TEACHER, MODERATION→COMMUNITY_MODERATOR")
                .containsExactly(
                        "COMMUNITY_MODERATOR→COMMUNITY_MODERATION",
                        "STUDENT→COMMUNITY_BOOKMARK",
                        "STUDENT→COMMUNITY_REPORT",
                        "TEACHER→COMMUNITY_BOOKMARK",
                        "TEACHER→COMMUNITY_FORK",
                        "TEACHER→COMMUNITY_PUBLISH",
                        "TEACHER→COMMUNITY_REPORT");
        assertThat(count(st, """
                        SELECT count(*) FROM user_roles ur
                        JOIN roles r ON r.id = ur.role_id
                        WHERE r.code = 'COMMUNITY_MODERATOR'
                        """))
                .as("COMMUNITY_MODERATOR ships dormant (granted to nobody)")
                .isZero();
    }

    // ==================================================================
    // staged-flyway helpers (V24 matrix pattern)
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
