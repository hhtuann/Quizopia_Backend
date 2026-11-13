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
 * V28 community-bookmarks migration matrix (ADR-006 §3 V28, issue
 * #45), following the staged-flyway pattern of
 * {@code V27CommunityModerationMigrationMatrixTest}:
 *
 * <ul>
 *   <li><b>FRESH</b> — an empty database migrates V1..V28 and ends with
 *       the bookmarks table, the UNIQUE(user_id, publication_id)
 *       constraint and the owner scan index.</li>
 *   <li><b>UPGRADE</b> — a database staged at V27 (with live community
 *       data) migrates to V28 with an identical end state; V27 rows
 *       are untouched.</li>
 *   <li><b>SEMANTICS (both legs)</b> — the UNIQUE constraint rejects a
 *       second row per (user, publication); the owner index carries
 *       the exact {@code (user_id, created_at DESC, id DESC)} shape;
 *       the FK policy is users RESTRICT / publication CASCADE.</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V28CommunityBookmarksMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    // ==================================================================
    // LEG 1 — FRESH: empty database → V28
    // ==================================================================
    @Test
    void freshDatabaseMigratesToV28WithBookmarksTable() throws Exception {
        String dbName = createDatabase("v28mx_fresh");
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
                        .as("migration chain reached V36")
                        .containsExactly("36");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // LEG 2 — UPGRADE: V27 with live community data → V28
    // ==================================================================
    @Test
    void stagedV27WithLivePublicationsUpgradesToV28() throws Exception {
        String dbName = createDatabase("v28mx_up");
        try {
            String url = urlFor(dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("27")
                    .load()
                    .migrate();

            long publicationId;
            long saverId;
            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                long teacherId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v28up%s', 'v28up%s@t.example.com', 'h', 'UP', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(System.nanoTime(), System.nanoTime()));
                saverId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v28ur%s', 'v28ur%s@t.example.com', 'h', 'UP', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(System.nanoTime(), System.nanoTime()));
                long examId = insert(st, """
                        INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                        VALUES (%d, 'V28E%s', 'Leg-2 exam', 'PUBLISHED', now(), 1)
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

                // V27-era data untouched by the upgrade.
                assertThat(count(st, """
                        SELECT count(*) FROM community_publications WHERE id = %d AND status = 'PUBLISHED'
                        """.formatted(publicationId)))
                        .as("pre-existing publication survives V28 unchanged")
                        .isEqualTo(1);

                // Cross-version flow: a V27-era publication receives a
                // bookmark under the V28 schema.
                insert(st, """
                        INSERT INTO community_bookmarks (user_id, publication_id)
                        VALUES (%d, %d)
                        """.formatted(saverId, publicationId));
                assertThat(count(st, """
                        SELECT count(*) FROM community_bookmarks WHERE publication_id = %d
                        """.formatted(publicationId)))
                        .isEqualTo(1);

                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                        .as("migration chain reached V36")
                        .containsExactly("36");
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
                          AND table_name = 'community_bookmarks'
                        """))
                .as("the V28 table exists")
                .containsExactly("community_bookmarks");

        assertThat(rows(st, """
                        SELECT indexname FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname IN ('uk_community_bookmarks_user_publication',
                                            'idx_community_bookmarks_owner')
                        ORDER BY indexname
                        """))
                .as("UNIQUE backing index + owner scan index exist")
                .containsExactly("idx_community_bookmarks_owner", "uk_community_bookmarks_user_publication");

        // The owner index is the exact §7 paged-scan shape: created_at
        // DESC then id DESC (not merely present).
        assertThat(rows(st, """
                        SELECT pg_get_indexdef(idx.indexrelid)
                        FROM pg_index idx
                        JOIN pg_class cls ON cls.oid = idx.indexrelid
                        WHERE cls.relname = 'idx_community_bookmarks_owner'
                        """))
                .first()
                .asString()
                .contains("created_at DESC")
                .contains("id DESC");
    }

    /** UNIQUE + FK semantics, both legs. */
    private static void assertSemantics(Statement st) throws SQLException {
        long ownerId = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v28s%s', 'v28s%s@t.example.com', 'h', 'S', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        long saver = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v28r%s', 'v28r%s@t.example.com', 'h', 'R', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        long examId = insert(st, """
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'V28S%s', 'Semantics exam', 'PUBLISHED', now(), 1)
                """.formatted(ownerId, System.nanoTime()));
        long publicationId = insert(st, """
                INSERT INTO community_publications (exam_id, publisher_user_id, title,
                                                    status, moderation_state)
                VALUES (%d, %d, 'Semantics publication', 'PUBLISHED', 'VISIBLE')
                """.formatted(examId, ownerId));

        // UNIQUE(user_id, publication_id): one shelf row per pair…
        insert(st, """
                INSERT INTO community_bookmarks (user_id, publication_id)
                VALUES (%d, %d)
                """.formatted(saver, publicationId));
        assertThatThrownBy(() -> insert(st, """
                        INSERT INTO community_bookmarks (user_id, publication_id)
                        VALUES (%d, %d)
                        """.formatted(saver, publicationId)))
                .as("UNIQUE constraint rejects a second row per (user, publication)")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_community_bookmarks_user_publication");

        // …while a different user may save the same publication.
        insert(st, """
                INSERT INTO community_bookmarks (user_id, publication_id)
                VALUES (%d, %d)
                """.formatted(ownerId, publicationId));

        // FK policy: users RESTRICT — no user hard-delete path opens up.
        assertThatThrownBy(() -> st.execute("""
                        DELETE FROM users WHERE id = %d
                        """.formatted(saver)))
                .as("fk_community_bookmarks_user RESTRICTs user deletion")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_community_bookmarks_user");

        // FK policy: publication CASCADE — the shelf never outlives its
        // source (tags/reports of the same publication cascade too).
        insert(st, """
                INSERT INTO community_reports (publication_id, reporter_user_id, reason_code)
                VALUES (%d, %d, 'SPAM')
                """.formatted(publicationId, saver));
        st.execute("DELETE FROM community_publications WHERE id = " + publicationId);
        assertThat(count(st, """
                        SELECT count(*) FROM community_bookmarks WHERE publication_id = %d
                        """.formatted(publicationId)))
                .as("bookmarks cascade with their publication")
                .isZero();
    }

    // ==================================================================
    // staged-flyway helpers (V27 matrix pattern)
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
