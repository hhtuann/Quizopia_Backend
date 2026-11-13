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
 * V31 community-social migration matrix (ADR-007 §3, issue #65),
 * following the staged-flyway pattern of
 * {@code V29CommunityForkMigrationMatrixTest}:
 *
 * <ul>
 *   <li><b>FRESH</b> — an empty database migrates V1..V31 and ends with
 *       the member-profile, like, rating, comment and comment-report
 *       tables, the ci-unique alias index, the ratings covering index
 *       and the widened moderation action CHECK.</li>
 *   <li><b>UPGRADE</b> — a database staged at V30 (with live community
 *       data incl. a V27-era moderation action row) migrates to V31
 *       with an identical end state; V30-era rows are untouched and the
 *       additive columns arrive NULL.</li>
 *   <li><b>SEMANTICS (both legs)</b> — rating CHECK 1..5; alias
 *       ci-uniqueness; comment/report CHECKs + the partial unique OPEN
 *       report; publication-side CASCADE vs users RESTRICT; the audit
 *       RESTRICT on comment_id/comment_report_id; COMMENT_* + all six
 *       legacy action values accepted; the RBAC grant map
 *       (COMMUNITY_LIKE/RATE/COMMENT → STUDENT + TEACHER).</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V31CommunitySocialMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    // ==================================================================
    // LEG 1 — FRESH: empty database → V31
    // ==================================================================
    @Test
    void freshDatabaseMigratesToV31WithCommunitySocialTables() throws Exception {
        String dbName = createDatabase("v31mx_fresh");
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
    // LEG 2 — UPGRADE: V30 with live community data → V31
    // ==================================================================
    @Test
    void stagedV30WithLiveCommunityDataUpgradesToV31() throws Exception {
        String dbName = createDatabase("v31mx_up");
        try {
            String url = urlFor(dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("30")
                    .load()
                    .migrate();

            long publicationId;
            long teacherId;
            long studentId;
            long reportId;
            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                long nano = System.nanoTime();
                teacherId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v31t%s', 'v31t%s@t.example.com', 'h', 'V31', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(nano, nano));
                studentId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v31s%s', 'v31s%s@t.example.com', 'h', 'VS', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(nano, nano));
                long examId = insert(st, """
                        INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                        VALUES (%d, 'V31E%s', 'Leg-2 source exam', 'PUBLISHED', now(), 1)
                        """.formatted(teacherId, nano));
                publicationId = insert(st, """
                        INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                            status, moderation_state, published_at)
                        VALUES (%d, %d, 'Leg-2 publication', 'V31-Alias', 'PUBLISHED', 'VISIBLE', now())
                        """.formatted(examId, teacherId));

                // Live V27-era audit + report rows: the widened CHECK
                // and the new columns must not disturb them.
                reportId = insert(st, """
                        INSERT INTO community_reports (publication_id, reporter_user_id, reason_code, status)
                        VALUES (%d, %d, 'SPAM', 'OPEN')
                        """.formatted(publicationId, studentId));
                insert(st, """
                        INSERT INTO community_moderation_actions
                            (actor_user_id, publication_id, report_id, action, from_moderation_state,
                             to_moderation_state, reason)
                        VALUES (%d, %d, %d, 'REPORT_DISMISS', 'VISIBLE', 'VISIBLE', 'leg-2 audit')
                        """.formatted(teacherId, publicationId, reportId));
            }

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                assertSchema(st);
                assertSemantics(st);

                // V30-era data untouched by the upgrade; the two new
                // audit columns arrived NULL for legacy rows.
                assertThat(count(st, """
                        SELECT count(*) FROM community_moderation_actions
                        WHERE reason = 'leg-2 audit' AND action = 'REPORT_DISMISS'
                          AND comment_id IS NULL AND comment_report_id IS NULL
                        """))
                        .as("legacy audit row survives V31 unchanged with NULL comment links")
                        .isEqualTo(1);

                // Cross-version flow: a V30-era publication collects a
                // like + a rating under the V31 schema, and a comment
                // moderation action can now be audited.
                execute(st, """
                        INSERT INTO community_likes (user_id, publication_id)
                        VALUES (%d, %d)
                        """.formatted(studentId, publicationId));
                execute(st, """
                        INSERT INTO community_ratings (user_id, publication_id, rating)
                        VALUES (%d, %d, 4)
                        """.formatted(studentId, publicationId));
                long commentId = insert(st, """
                        INSERT INTO community_comments (publication_id, author_user_id, author_alias, body)
                        VALUES (%d, %d, 'LegacyUser', 'cross-version comment')
                        """.formatted(publicationId, studentId));
                insert(st, """
                        INSERT INTO community_moderation_actions
                            (actor_user_id, publication_id, comment_id, action,
                             from_moderation_state, to_moderation_state, reason)
                        VALUES (%d, %d, %d, 'COMMENT_HIDE', 'VISIBLE', 'HIDDEN', 'leg-2 hide')
                        """.formatted(teacherId, publicationId, commentId));

                assertThat(count(st, """
                        SELECT count(*) FROM community_likes WHERE publication_id = %d
                        """.formatted(publicationId)))
                        .isEqualTo(1);
                assertThat(count(st, """
                        SELECT count(*) FROM community_ratings WHERE publication_id = %d
                        """.formatted(publicationId)))
                        .isEqualTo(1);

                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
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
                          AND table_name IN ('community_member_profiles', 'community_likes',
                                             'community_ratings', 'community_comments',
                                             'community_comment_reports')
                        ORDER BY table_name
                        """))
                .as("all five V31 tables exist")
                .containsExactly("community_comment_reports", "community_comments",
                        "community_likes", "community_member_profiles", "community_ratings");

        // The pinned index names (alias ci-unique + ratings covering).
        assertThat(rows(st, """
                        SELECT indexname FROM pg_indexes
                        WHERE indexname IN ('uk_community_member_profiles_alias_ci',
                                            'idx_community_ratings_publication',
                                            'idx_community_likes_publication',
                                            'uk_community_comment_reports_open_per_reporter')
                        ORDER BY indexname
                        """))
                .containsExactly("idx_community_likes_publication",
                        "idx_community_ratings_publication",
                        "uk_community_comment_reports_open_per_reporter",
                        "uk_community_member_profiles_alias_ci");

        // The covering index carries INCLUDE (rating).
        assertThat(rows(st, """
                        SELECT indexdef FROM pg_indexes
                        WHERE indexname = 'idx_community_ratings_publication'
                        """))
                .singleElement()
                .asString()
                .contains("INCLUDE (rating)");

        // Composite PKs on (user_id, publication_id) for likes/ratings.
        for (String table : new String[]{"community_likes", "community_ratings"}) {
            assertThat(rows(st, """
                            SELECT a.attname FROM pg_index i
                            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                            WHERE i.indrelid = '%s'::regclass AND i.indisprimary
                            ORDER BY a.attnum
                            """.formatted(table)))
                    .as("%s primary key is (user_id, publication_id)", table)
                    .containsExactly("user_id", "publication_id");
        }

        // The widened moderation action CHECK + the two new audit columns.
        assertThat(rows(st, """
                        SELECT column_name FROM information_schema.columns
                        WHERE table_name = 'community_moderation_actions'
                          AND column_name IN ('comment_id', 'comment_report_id')
                        ORDER BY column_name
                        """))
                .as("V31 adds comment_id + comment_report_id audit links")
                .containsExactly("comment_id", "comment_report_id");
        assertThat(rows(st, """
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint
                        WHERE conname = 'chk_community_moderation_actions_action'
                        """))
                .singleElement()
                .asString()
                .contains("COMMENT_HIDE", "COMMENT_RESTORE",
                        "COMMENT_REPORT_DISMISS", "COMMENT_REPORT_RESOLVE");
    }

    /** CHECK + unique + FK semantics + RBAC grant map, both legs. */
    private static void assertSemantics(Statement st) throws SQLException {
        long nano = System.nanoTime();
        long teacherId = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v31st%s', 'v31st%s@t.example.com', 'h', 'ST', 'ACTIVE', now(), 0, 0, now())
                """.formatted(nano, nano));
        long raterId = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v31sr%s', 'v31sr%s@t.example.com', 'h', 'SR', 'ACTIVE', now(), 0, 0, now())
                """.formatted(nano, nano));
        long examId = insert(st, """
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'V31X%s', 'Semantics exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, nano));
        long publicationId = insert(st, """
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Semantics publication', 'Sem-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));

        // --- rating CHECK 1..5 --------------------------------------
        execute(st, "INSERT INTO community_ratings (user_id, publication_id, rating)"
                + " VALUES (%d, %d, 1)".formatted(raterId, publicationId));
        execute(st, "UPDATE community_ratings SET rating = 5 WHERE user_id = %d".formatted(raterId));
        for (int bad : new int[]{0, 6}) {
            assertThatThrownBy(() -> execute(st,
                    "UPDATE community_ratings SET rating = %d WHERE user_id = %d"
                            .formatted(bad, raterId)))
                    .as("rating %d violates chk_community_ratings_rating", bad)
                    .isInstanceOf(SQLException.class);
        }
        execute(st, "DELETE FROM community_ratings WHERE user_id = %d".formatted(raterId));

        // --- alias ci-uniqueness ------------------------------------
        execute(st, "INSERT INTO community_member_profiles (user_id, alias)"
                + " VALUES (%d, 'UniqueAlias')".formatted(raterId));
        assertThatThrownBy(() -> execute(st,
                "INSERT INTO community_member_profiles (user_id, alias) VALUES (%d, 'uniquealias')"
                        .formatted(teacherId)))
                .as("case-insensitive alias collision violates uk_community_member_profiles_alias_ci")
                .isInstanceOf(SQLException.class);

        // --- comments: state CHECK, one row per (user, publication) rating
        long commentId = insert(st, """
                INSERT INTO community_comments (publication_id, author_user_id, author_alias, body)
                VALUES (%d, %d, 'UniqueAlias', 'semantics body')
                """.formatted(publicationId, raterId));
        assertThatThrownBy(() -> execute(st,
                "UPDATE community_comments SET state = 'PENDING' WHERE id = %d".formatted(commentId)))
                .as("comment state CHECK admits VISIBLE/HIDDEN/DELETED only")
                .isInstanceOf(SQLException.class);
        insert(st, "UPDATE community_comments SET state = 'HIDDEN' WHERE id = %d".formatted(commentId));
        insert(st, "UPDATE community_comments SET state = 'DELETED', body = '' WHERE id = %d"
                .formatted(commentId));

        // --- comment reports: status CHECK + partial unique OPEN -----
        insert(st, """
                INSERT INTO community_comment_reports (comment_id, reporter_user_id, reason_code)
                VALUES (%d, %d, 'SPAM')
                """.formatted(commentId, raterId));
        assertThatThrownBy(() -> execute(st, """
                INSERT INTO community_comment_reports (comment_id, reporter_user_id, reason_code)
                VALUES (%d, %d, 'OTHER')
                """.formatted(commentId, raterId)))
                .as("second OPEN report per (reporter, comment) violates the partial unique index")
                .isInstanceOf(SQLException.class);
        insert(st, """
                UPDATE community_comment_reports SET status = 'DISMISSED', handled_by = %d,
                        handled_at = now(), resolution_note = 'ok'
                WHERE comment_id = %d AND reporter_user_id = %d
                """.formatted(teacherId, commentId, raterId));
        insert(st, """
                INSERT INTO community_comment_reports (comment_id, reporter_user_id, reason_code)
                VALUES (%d, %d, 'OTHER')
                """.formatted(commentId, raterId));
        assertThatThrownBy(() -> execute(st, """
                INSERT INTO community_comment_reports (comment_id, reporter_user_id, reason_code, status)
                VALUES (%d, %d, 'SPAM', 'PENDING')
                """.formatted(commentId, raterId)))
                .as("comment report status CHECK admits OPEN/DISMISSED/RESOLVED only")
                .isInstanceOf(SQLException.class);

        // --- widened action CHECK: all six legacy + four COMMENT_* ---
        for (String action : new String[]{"HIDE", "RESTORE", "REPORT_DISMISS", "REPORT_RESOLVE",
                "MODERATOR_GRANTED", "MODERATOR_REVOKED", "COMMENT_HIDE", "COMMENT_RESTORE",
                "COMMENT_REPORT_DISMISS", "COMMENT_REPORT_RESOLVE"}) {
            insert(st, """
                    INSERT INTO community_moderation_actions
                        (actor_user_id, publication_id, comment_id, comment_report_id, action, reason)
                    VALUES (%d, %d, %d, (SELECT id FROM community_comment_reports
                                         WHERE comment_id = %d AND reporter_user_id = %d
                                           AND status = 'OPEN'),
                            '%s', 'semantics %s')
                    """.formatted(teacherId, publicationId, commentId, commentId, raterId,
                    action, action));
        }
        assertThatThrownBy(() -> execute(st, """
                INSERT INTO community_moderation_actions (actor_user_id, publication_id, action, reason)
                VALUES (%d, %d, 'COMMENT_DELETE', 'not a value')
                """.formatted(teacherId, publicationId)))
                .as("the widened CHECK still rejects unknown action values")
                .isInstanceOf(SQLException.class);

        // --- FK policy (§3) ----------------------------------------
        // Audit RESTRICT first, with the ten rows above still in place:
        // neither the audited comment, its publication, nor the linked
        // OPEN report may be deleted while audit rows reference them.
        assertThatThrownBy(() -> execute(st,
                "DELETE FROM community_comments WHERE id = %d".formatted(commentId)))
                .as("comment delete is RESTRICTed by audit comment_id")
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(st,
                "DELETE FROM community_publications WHERE id = %d".formatted(publicationId)))
                .as("publication delete is RESTRICTed by audit publication_id")
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(st, """
                        DELETE FROM community_comment_reports
                        WHERE comment_id = %d AND reporter_user_id = %d AND status = 'OPEN'
                        """.formatted(commentId, raterId)))
                .as("comment report delete is RESTRICTed by audit comment_report_id")
                .isInstanceOf(SQLException.class);

        // Publication-side CASCADE on a dedicated publication (fresh
        // exam — publications are UNIQUE per exam) with NO audit rows:
        // likes, ratings, comments and comment reports die with the
        // publication.
        long cascadeExamId = insert(st, """
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'V31C%s', 'Cascade exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, nano));
        long cascadePubId = insert(st, """
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Cascade publication', 'CS-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(cascadeExamId, teacherId));
        execute(st, "INSERT INTO community_likes (user_id, publication_id) VALUES (%d, %d)"
                .formatted(raterId, cascadePubId));
        execute(st, "INSERT INTO community_ratings (user_id, publication_id, rating)"
                + " VALUES (%d, %d, 3)".formatted(raterId, cascadePubId));
        long cascadeCommentId = insert(st, """
                INSERT INTO community_comments (publication_id, author_user_id, author_alias, body)
                VALUES (%d, %d, 'UniqueAlias', 'cascade body')
                """.formatted(cascadePubId, raterId));
        insert(st, """
                INSERT INTO community_comment_reports (comment_id, reporter_user_id, reason_code)
                VALUES (%d, %d, 'SPAM')
                """.formatted(cascadeCommentId, raterId));
        execute(st, "DELETE FROM community_publications WHERE id = %d".formatted(cascadePubId));
        assertThat(count(st, """
                        SELECT count(*) FROM community_likes WHERE publication_id = %d
                        """.formatted(cascadePubId)))
                .as("likes cascade with the publication")
                .isZero();
        assertThat(count(st, """
                        SELECT count(*) FROM community_ratings WHERE publication_id = %d
                        """.formatted(cascadePubId)))
                .as("ratings cascade with the publication")
                .isZero();
        assertThat(count(st, """
                        SELECT count(*) FROM community_comments WHERE publication_id = %d
                        """.formatted(cascadePubId)))
                .as("comments cascade with the publication")
                .isZero();
        assertThat(count(st, """
                        SELECT count(*) FROM community_comment_reports WHERE comment_id = %d
                        """.formatted(cascadeCommentId)))
                .as("comment reports cascade with the comment")
                .isZero();

        // Users RESTRICT: an interacting user cannot be deleted while
        // social rows reference them (profile, comment, report or like).
        long otherExamId = insert(st, """
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'V31U%s', 'Users-restrict exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, nano));
        long otherPub = insert(st, """
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Users-restrict publication', 'UR-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(otherExamId, teacherId));
        execute(st, "INSERT INTO community_likes (user_id, publication_id) VALUES (%d, %d)"
                .formatted(raterId, otherPub));
        assertThatThrownBy(() -> execute(st, "DELETE FROM users WHERE id = %d".formatted(raterId)))
                .as("user delete is RESTRICTed while social rows reference them")
                .isInstanceOf(SQLException.class);
        execute(st, "DELETE FROM community_likes WHERE user_id = %d AND publication_id = %d"
                .formatted(raterId, otherPub));

        // --- RBAC grant map ----------------------------------------
        assertThat(rows(st, """
                        SELECT r.code || ':' || p.code
                        FROM role_permissions rp
                        JOIN roles r ON r.id = rp.role_id
                        JOIN permissions p ON p.id = rp.permission_id
                        WHERE p.code IN ('COMMUNITY_LIKE', 'COMMUNITY_RATE', 'COMMUNITY_COMMENT')
                        ORDER BY 1
                        """))
                .as("COMMUNITY_LIKE/RATE/COMMENT are granted to exactly STUDENT + TEACHER")
                .containsExactly("STUDENT:COMMUNITY_COMMENT", "STUDENT:COMMUNITY_LIKE",
                        "STUDENT:COMMUNITY_RATE", "TEACHER:COMMUNITY_COMMENT",
                        "TEACHER:COMMUNITY_LIKE", "TEACHER:COMMUNITY_RATE");
        assertThat(count(st, """
                        SELECT count(*) FROM role_permissions rp
                        JOIN permissions p ON p.id = rp.permission_id
                        WHERE p.code LIKE 'COMMUNITY_%%'
                        """))
                .as("V26 seeded 7 COMMUNITY_* grants; V31 adds exactly 6")
                .isEqualTo(13);
    }

    // ==================================================================
    // staged-flyway helpers (V29 matrix pattern)
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
