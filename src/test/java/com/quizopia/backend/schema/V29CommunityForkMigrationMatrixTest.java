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
 * V29 community-fork-records migration matrix (ADR-006 §3 V29, issue
 * #47), following the staged-flyway pattern of
 * {@code V28CommunityBookmarksMigrationMatrixTest}:
 *
 * <ul>
 *   <li><b>FRESH</b> — an empty database migrates V1..V29 and ends with
 *       the fork ledger table, the UNIQUE(actor, idempotency_key_hash)
 *       and UNIQUE(target_exam_id) constraints.</li>
 *   <li><b>UPGRADE</b> — a database staged at V28 (with live community
 *       data) migrates to V29 with an identical end state; V28 rows are
 *       untouched and a fork record flows through the V29 schema.</li>
 *   <li><b>SEMANTICS (both legs)</b> — the CHECK rejects non-64-hex
 *       hashes; the UNIQUE pair rejects a second row per
 *       (actor, key); every FK RESTRICTs (actor user, source
 *       publication, source/target exams).</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V29CommunityForkMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    // ==================================================================
    // LEG 1 — FRESH: empty database → V29
    // ==================================================================
    @Test
    void freshDatabaseMigratesToV29WithForkRecordsTable() throws Exception {
        String dbName = createDatabase("v29mx_fresh");
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
    // LEG 2 — UPGRADE: V28 with live community data → V29
    // ==================================================================
    @Test
    void stagedV28WithLivePublicationsUpgradesToV29() throws Exception {
        String dbName = createDatabase("v29mx_up");
        try {
            String url = urlFor(dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("28")
                    .load()
                    .migrate();

            long publicationId;
            long actorId;
            long sourceExamId;
            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                long teacherId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v29up%s', 'v29up%s@t.example.com', 'h', 'UP', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(System.nanoTime(), System.nanoTime()));
                actorId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v29ur%s', 'v29ur%s@t.example.com', 'h', 'UR', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(System.nanoTime(), System.nanoTime()));
                sourceExamId = insert(st, """
                        INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                        VALUES (%d, 'V29S%s', 'Leg-2 source exam', 'PUBLISHED', now(), 1)
                        """.formatted(teacherId, System.nanoTime()));
                publicationId = insert(st, """
                        INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                            status, moderation_state, reuse_policy)
                        VALUES (%d, %d, 'Leg-2 publication', 'V29-Alias', 'PUBLISHED', 'VISIBLE', 'ALLOW_FORK')
                        """.formatted(sourceExamId, teacherId));
            }

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                assertSchema(st);
                assertSemantics(st);

                // V28-era data untouched by the upgrade.
                assertThat(count(st, """
                        SELECT count(*) FROM community_publications WHERE id = %d AND reuse_policy = 'ALLOW_FORK'
                        """.formatted(publicationId)))
                        .as("pre-existing publication survives V29 unchanged")
                        .isEqualTo(1);

                // Cross-version flow: a V28-era publication is forked
                // under the V29 schema.
                long targetExamId = insert(st, """
                        INSERT INTO exams (created_by_user_id, code, title, status, total_points)
                        VALUES (%d, 'V29T%s', 'Leg-2 fork', 'DRAFT', 0)
                        """.formatted(actorId, System.nanoTime()));
                insert(st, """
                        INSERT INTO community_fork_records (actor_user_id, idempotency_key_hash,
                                                            source_publication_id, source_exam_id,
                                                            target_exam_id, source_title_snapshot,
                                                            source_alias_snapshot)
                        VALUES (%d, repeat('a', 64), %d, %d, %d, 'Leg-2 publication', 'V29-Alias')
                        """.formatted(actorId, publicationId, sourceExamId, targetExamId));
                assertThat(count(st, """
                        SELECT count(*) FROM community_fork_records WHERE source_publication_id = %d
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
                          AND table_name = 'community_fork_records'
                        """))
                .as("the V29 table exists")
                .containsExactly("community_fork_records");

        assertThat(rows(st, """
                        SELECT conname FROM pg_constraint
                        WHERE conrelid = 'community_fork_records'::regclass
                          AND conname IN ('uk_community_fork_records_actor_key',
                                          'uk_community_fork_records_target_exam',
                                          'ck_community_fork_records_key_hex')
                        ORDER BY conname
                        """))
                .as("idempotency + target uniques and the hex CHECK exist")
                .containsExactly("ck_community_fork_records_key_hex",
                        "uk_community_fork_records_actor_key",
                        "uk_community_fork_records_target_exam");
    }

    /** CHECK + UNIQUE + FK RESTRICT semantics, both legs. */
    private static void assertSemantics(Statement st) throws SQLException {
        long ownerId = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v29s%s', 'v29s%s@t.example.com', 'h', 'S', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        long actor = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v29a%s', 'v29a%s@t.example.com', 'h', 'A', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        long sourceExamId = insert(st, """
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'V29X%s', 'Semantics exam', 'PUBLISHED', now(), 1)
                """.formatted(ownerId, System.nanoTime()));
        long publicationId = insert(st, """
                INSERT INTO community_publications (exam_id, publisher_user_id, title,
                                                    status, moderation_state, reuse_policy)
                VALUES (%d, %d, 'Semantics publication', 'PUBLISHED', 'VISIBLE', 'ALLOW_FORK')
                """.formatted(sourceExamId, ownerId));
        long targetExamId = insert(st, """
                INSERT INTO exams (created_by_user_id, code, title, status, total_points)
                VALUES (%d, 'V29Y%s', 'Semantics fork', 'DRAFT', 0)
                """.formatted(actor, System.nanoTime()));
        String validHash = "ab".repeat(32);

        // The hex CHECK pins the storage shape: 64 lowercase hex only.
        assertThatThrownBy(() -> insert(st, """
                INSERT INTO community_fork_records (actor_user_id, idempotency_key_hash,
                                                    source_publication_id, source_exam_id,
                                                    target_exam_id)
                VALUES (%d, 'not-hex!', %d, %d, %d)
                """.formatted(actor, publicationId, sourceExamId, targetExamId)))
                .as("CHECK rejects a non-64-hex idempotency key hash")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_community_fork_records_key_hex");
        assertThatThrownBy(() -> insert(st, """
                INSERT INTO community_fork_records (actor_user_id, idempotency_key_hash,
                                                    source_publication_id, source_exam_id,
                                                    target_exam_id)
                VALUES (%d, repeat('a', 63), %d, %d, %d)
                """.formatted(actor, publicationId, sourceExamId, targetExamId)))
                .as("CHECK rejects a 63-char hash")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_community_fork_records_key_hex");

        // UNIQUE(actor, idempotency_key_hash): one target per (actor, key).
        insert(st, """
                INSERT INTO community_fork_records (actor_user_id, idempotency_key_hash,
                                                    source_publication_id, source_exam_id,
                                                    target_exam_id)
                VALUES (%d, '%s', %d, %d, %d)
                """.formatted(actor, validHash, publicationId, sourceExamId, targetExamId));
        long otherTarget = insert(st, """
                INSERT INTO exams (created_by_user_id, code, title, status, total_points)
                VALUES (%d, 'V29Z%s', 'Second fork', 'DRAFT', 0)
                """.formatted(actor, System.nanoTime()));
        assertThatThrownBy(() -> insert(st, """
                        INSERT INTO community_fork_records (actor_user_id, idempotency_key_hash,
                                                            source_publication_id, source_exam_id,
                                                            target_exam_id)
                        VALUES (%d, '%s', %d, %d, %d)
                        """.formatted(actor, validHash, publicationId, sourceExamId, otherTarget)))
                .as("UNIQUE rejects a second row per (actor, key)")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_community_fork_records_actor_key");

        // FK policy: users/exams/publications all RESTRICT — no delete
        // path opens up under the ledger. (The actor and source exam
        // are referenced through earlier RESTRICT FKs as well — exams
        // ownership, publication.exam_id — so only the absence of ANY
        // delete path is asserted there; the ledger-only targets prove
        // the V29 constraints by name.)
        assertThatThrownBy(() -> st.execute("DELETE FROM users WHERE id = " + actor))
                .as("no user delete path opens under the ledger")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("violates foreign key constraint");
        assertThatThrownBy(() -> st.execute("DELETE FROM exams WHERE id = " + sourceExamId))
                .as("no source-exam delete path opens under the ledger")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("violates foreign key constraint");
        assertThatThrownBy(() -> st.execute("DELETE FROM exams WHERE id = " + targetExamId))
                .as("fk_community_fork_records_target_exam RESTRICTs exam deletion")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_community_fork_records_target_exam");
        assertThatThrownBy(() -> st.execute("DELETE FROM community_publications WHERE id = " + publicationId))
                .as("fk_community_fork_records_source_publication RESTRICTs publication deletion")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_community_fork_records_source_publication");
    }

    // ==================================================================
    // staged-flyway helpers (V28 matrix pattern)
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
