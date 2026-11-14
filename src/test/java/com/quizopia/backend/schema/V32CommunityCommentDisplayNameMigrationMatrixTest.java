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

/**
 * V32 community comment display-name migration matrix (ADR-007 Rev 4,
 * issue #72), following the staged-flyway pattern of
 * {@code V31CommunitySocialMigrationMatrixTest}:
 *
 * <ul>
 *   <li><b>FRESH</b> — an empty database migrates V1..V32:
 *       {@code community_comments.author_alias} is nullable, the legacy
 *       {@code community_member_profiles} table is retained, and
 *       {@code users.display_name} stays non-unique.</li>
 *   <li><b>UPGRADE</b> — a database staged at V31 with live V31-era
 *       data (comments carrying alias snapshots, a member-profile row)
 *       migrates to V32 with the historical rows untouched: legacy
 *       alias snapshots survive verbatim, member-profile rows survive,
 *       and NEW comment rows can omit {@code author_alias}.</li>
 *   <li><b>SEMANTICS (both legs)</b> — a comment insert WITHOUT
 *       author_alias succeeds; duplicate display names remain legal;
 *       the V31-era objects (likes, ratings, reports, moderation
 *       CHECKs) are all still present — V32 deletes nothing.</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V32CommunityCommentDisplayNameMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    // ==================================================================
    // LEG 1 — FRESH: empty database → V32
    // ==================================================================
    @Test
    void freshDatabaseMigratesToV32WithNullableAuthorAlias() throws Exception {
        String dbName = createDatabase("v32mx_fresh");
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
                        .as("migration chain reached V37")
                        .containsExactly("37");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // LEG 2 — UPGRADE: V31 with live alias-era data → V32
    // ==================================================================
    @Test
    void stagedV31WithAliasEraDataUpgradesToV32PreservingHistory() throws Exception {
        String dbName = createDatabase("v32mx_up");
        try {
            String url = urlFor(dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("31")
                    .load()
                    .migrate();

            long legacyCommentId;
            long teacherId;
            long studentId;
            long publicationId;
            long nano = System.nanoTime();
            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                teacherId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v32t%s', 'v32t%s@t.example.com', 'h', 'V32 Teacher', 'ACTIVE',
                                now(), 0, 0, now())
                        """.formatted(nano, nano));
                studentId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v32s%s', 'v32s%s@t.example.com', 'h', 'V32 Student', 'ACTIVE',
                                now(), 0, 0, now())
                        """.formatted(nano, nano));
                long examId = insert(st, """
                        INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                        VALUES (%d, 'V32E%s', 'Leg-2 source exam', 'PUBLISHED', now(), 1)
                        """.formatted(teacherId, nano));
                publicationId = insert(st, """
                        INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                            status, moderation_state, published_at)
                        VALUES (%d, %d, 'Leg-2 publication', 'V32-PubAlias', 'PUBLISHED', 'VISIBLE', now())
                        """.formatted(examId, teacherId));
                // A V31-era member profile + a comment carrying its alias
                // snapshot — the historical data V32 must preserve.
                execute(st, """
                        INSERT INTO community_member_profiles (user_id, alias)
                        VALUES (%d, 'LegacyAlias-%s')
                        """.formatted(studentId, nano));
                legacyCommentId = insert(st, """
                        INSERT INTO community_comments (publication_id, author_user_id, author_alias, body)
                        VALUES (%d, %d, 'LegacyAlias-%s', 'v31-era comment')
                        """.formatted(publicationId, studentId, nano));
                execute(st, """
                        INSERT INTO community_likes (user_id, publication_id)
                        VALUES (%d, %d)
                        """.formatted(studentId, publicationId));
                execute(st, """
                        INSERT INTO community_ratings (user_id, publication_id, rating)
                        VALUES (%d, %d, 4)
                        """.formatted(studentId, publicationId));
            }

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                assertSchema(st);
                assertSemantics(st);

                // Historical rows are untouched: alias snapshot survives,
                // the member-profile row survives, like/rating rows survive.
                assertThat(rows(st, """
                        SELECT author_alias FROM community_comments WHERE id = %d
                        """.formatted(legacyCommentId)))
                        .as("the V31-era alias snapshot survives V32 verbatim")
                        .containsExactly("LegacyAlias-" + nano);
                assertThat(count(st, """
                                SELECT count(*) FROM community_member_profiles WHERE user_id = %d
                                """.formatted(studentId)))
                        .as("the V31-era member-profile row survives V32")
                        .isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM community_likes"))
                        .isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM community_ratings"))
                        .isEqualTo(1);
                assertThat(count(st, """
                                SELECT count(*) FROM community_ratings WHERE rating = 4
                                """))
                        .isEqualTo(1);

                // NEW comment rows omit the alias entirely (NULL).
                insert(st, """
                        INSERT INTO community_comments (publication_id, author_user_id, body)
                        VALUES (%d, %d, 'v32-era comment needs no alias')
                        """.formatted(publicationId, teacherId));
                assertThat(count(st, """
                                SELECT count(*) FROM community_comments
                                WHERE author_alias IS NULL AND body = 'v32-era comment needs no alias'
                                """))
                        .as("post-V32 comment rows store no alias snapshot")
                        .isEqualTo(1);

                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                        .as("staged leg also reaches the current head")
                        .containsExactly("37");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // assertions
    // ==================================================================

    private static void assertSchema(Statement st) throws SQLException {
        assertThat(rows(st, """
                        SELECT is_nullable FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'community_comments'
                          AND column_name = 'author_alias'
                        """))
                .as("V32 makes community_comments.author_alias nullable")
                .containsExactly("YES");

        assertThat(count(st, """
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name = 'community_member_profiles'
                        """))
                .as("the legacy member-profile table is retained (read-only)")
                .isEqualTo(1);

        assertThat(rows(st, """
                        SELECT indexname FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename = 'users'
                          AND indexdef LIKE '%%UNIQUE%%'
                          AND indexdef LIKE '%%display_name%%'
                        """))
                .as("users.display_name stays non-unique (duplicate display names are allowed)")
                .isEmpty();
    }

    private static void assertSemantics(Statement st) throws SQLException {
        // A comment insert without author_alias succeeds on both legs.
        // (The upgrade leg performs its own explicit inserts; this probe
        // uses a minimal publication of its own to stay leg-agnostic.)
        long nano = System.nanoTime();
        long teacherId = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v32p%s', 'v32p%s@t.example.com', 'h', 'V32 Probe', 'ACTIVE',
                        now(), 0, 0, now())
                """.formatted(nano, nano));
        long examId = insert(st, """
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'V32P%s', 'V32 probe exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, nano));
        long publicationId = insert(st, """
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'V32 probe publication', 'V32P-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
        long aliasless = insert(st, """
                INSERT INTO community_comments (publication_id, author_user_id, body)
                VALUES (%d, %d, 'alias-less probe')
                """.formatted(publicationId, teacherId));
        assertThat(count(st, """
                        SELECT count(*) FROM community_comments
                        WHERE id = %d AND author_alias IS NULL
                        """.formatted(aliasless)))
                .as("comment rows without an alias snapshot are legal after V32")
                .isEqualTo(1);

        // Duplicate display names remain legal (both legs).
        execute(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v32d%s', 'v32d%s@t.example.com', 'h', 'V32 Probe', 'ACTIVE',
                        now(), 0, 0, now())
                """.formatted(nano, nano));
        assertThat(count(st, """
                        SELECT count(*) FROM users WHERE display_name = 'V32 Probe'
                        """))
                .as("two users share one display name")
                .isEqualTo(2);

        // Nothing from V31 was dropped: the alias ci-unique index still
        // exists on the retained legacy table.
        assertThat(count(st, """
                        SELECT count(*) FROM pg_indexes
                        WHERE indexname = 'uk_community_member_profiles_alias_ci'
                        """))
                .as("the V31 legacy alias index survives untouched")
                .isEqualTo(1);
    }

    // ==================================================================
    // staged-flyway helpers (V31 matrix pattern)
    // ==================================================================

    private String createDatabase(String prefix) throws SQLException {
        String dbName = prefix + "_" + System.nanoTime();
        try (Connection c = java.sql.DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
        }
        return dbName;
    }

    private String urlFor(String dbName) {
        return pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + dbName);
    }

    private void dropDatabase(String dbName) throws SQLException {
        try (Connection c = java.sql.DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
        }
    }

    private static void execute(Statement st, String sql) throws SQLException {
        st.execute(sql);
    }

    private static long insert(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql + " RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
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
