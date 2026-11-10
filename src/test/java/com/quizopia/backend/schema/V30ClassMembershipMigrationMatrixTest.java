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
 * V30 class-membership-periods migration matrix (CLASS-M3, issue #54),
 * following the staged-flyway pattern of
 * {@code V29CommunityForkMigrationMatrixTest}:
 *
 * <ul>
 *   <li><b>FRESH</b> — an empty database migrates V1..V30 with the history
 *       table, partial-unique open-period index, both triggers, and the
 *       verification-compatible invariants.</li>
 *   <li><b>UPGRADE</b> — a database staged at V29 with live memberships
 *       (including a pre-V30 departure, which leaves no recoverable
 *       evidence) migrates to V30: current members are backfilled with
 *       open periods anchored at {@code added_at}, the departed member
 *       gains nothing (honest gap), and post-migration membership writes
 *       flow through the triggers.</li>
 *   <li><b>SEMANTICS (both legs)</b> — one open period per member
 *       (partial unique), interval CHECK, epoch ordering on rejoin.</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V30ClassMembershipMigrationMatrixTest {

    @Autowired
    private PostgreSQLContainer pg;

    // ==================================================================
    // LEG 1 — FRESH: empty database → V30
    // ==================================================================

    @Test
    void freshDatabaseMigratesToV30WithMembershipPeriods() throws Exception {
        String dbName = createDatabase("v30mx_fresh");
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
                        .as("migration chain reached V32")
                        .containsExactly("32");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // LEG 2 — UPGRADE: V29 with live memberships → V30
    // ==================================================================

    @Test
    void stagedV29WithLiveMembershipsUpgradesToV30() throws Exception {
        String dbName = createDatabase("v30mx_up");
        try {
            String url = urlFor(dbName);
            String user = pg.getUsername();
            String pass = pg.getPassword();

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target("29")
                    .load()
                    .migrate();

            long classroomId;
            long stayingMember;
            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                long teacherId = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v30t%s', 'v30t%s@t.example.com', 'h', 'T30', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(System.nanoTime(), System.nanoTime()));
                long studentA = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v30a%s', 'v30a%s@t.example.com', 'h', 'A30', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(System.nanoTime(), System.nanoTime()));
                long studentB = insert(st, """
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           email_verified_at, token_version, failed_login_attempts,
                                           password_changed_at)
                        VALUES ('v30b%s', 'v30b%s@t.example.com', 'h', 'B30', 'ACTIVE', now(), 0, 0, now())
                        """.formatted(System.nanoTime(), System.nanoTime()));
                classroomId = insert(st, """
                        INSERT INTO classrooms (owner_user_id, code, name)
                        VALUES (%d, 'V30C%s', 'Leg-2 class')
                        """.formatted(teacherId, System.nanoTime()));
                // Current member (stays across the migration).
                insert(st, """
                        INSERT INTO classroom_members (classroom_id, user_id, added_at)
                        VALUES (%d, %d, now() - interval '30 days')
                        """.formatted(classroomId, studentA));
                stayingMember = studentA;
                // Pre-V30 member who DEPARTED before the migration: the row is
                // gone at V29, so V30 must NOT fabricate history for them.
                insert(st, """
                        INSERT INTO classroom_members (classroom_id, user_id)
                        VALUES (%d, %d)
                        """.formatted(classroomId, studentB));
                st.executeUpdate("DELETE FROM classroom_members WHERE classroom_id = "
                        + classroomId + " AND user_id = " + studentB);
            }

            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                assertSchema(st);

                // Backfill: exactly one OPEN period for the staying member,
                // anchored at the live row's added_at (honest evidence).
                assertThat(rows(st, """
                        SELECT (p.joined_at = cm.added_at)::text FROM classroom_membership_periods p
                        JOIN classroom_members cm ON cm.classroom_id = p.classroom_id
                                                 AND cm.user_id = p.user_id
                        WHERE p.classroom_id = %d AND p.user_id = %d AND p.left_at IS NULL
                        """.formatted(classroomId, stayingMember)))
                        .as("staying member backfilled with an open period at added_at")
                        .containsExactly("true");

                // The pre-V30 departure gains NOTHING (no fabrication).
                assertThat(count(st, "SELECT count(*) FROM classroom_membership_periods"))
                        .as("exactly one period row exists — the backfilled member only")
                        .isEqualTo(1);

                // Post-migration writes flow through the triggers.
                st.executeUpdate("""
                        INSERT INTO classroom_members (classroom_id, user_id)
                        VALUES (%d, (SELECT id FROM users WHERE username LIKE 'v30b%%' LIMIT 1))
                        """.formatted(classroomId));
                st.executeUpdate("""
                        DELETE FROM classroom_members
                        WHERE classroom_id = %d
                          AND user_id = (SELECT id FROM users WHERE username LIKE 'v30b%%' LIMIT 1)
                        """.formatted(classroomId));
                assertThat(rows(st, """
                        SELECT (left_at IS NOT NULL)::text FROM classroom_membership_periods p
                        WHERE p.classroom_id = %d
                          AND p.user_id = (SELECT id FROM users WHERE username LIKE 'v30b%%' LIMIT 1)
                        """.formatted(classroomId)))
                        .as("post-migration membership cycle captured as a closed epoch")
                        .containsExactly("true");

                assertSemantics(st);
                assertThat(rows(st, """
                        SELECT version FROM flyway_schema_history WHERE success
                        ORDER BY installed_rank DESC LIMIT 1
                        """))
                        .containsExactly("32");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    // ==================================================================
    // shared assertions
    // ==================================================================

    private static void assertSchema(Statement st) throws SQLException {
        assertThat(rows(st, """
                        SELECT indexname FROM pg_indexes
                        WHERE tablename = 'classroom_membership_periods'
                          AND indexname IN ('uk_cmp_one_open', 'idx_cmp_cohort')
                        ORDER BY indexname
                        """))
                .as("open-period partial unique + cohort index exist")
                .containsExactly("idx_cmp_cohort", "uk_cmp_one_open");
        assertThat(rows(st, """
                        SELECT tgname FROM pg_trigger
                        WHERE tgrelid = 'classroom_members'::regclass AND NOT tgisinternal
                        ORDER BY tgname
                        """))
                .as("history triggers installed on classroom_members")
                .containsExactly("tg_classroom_members_hist_del", "tg_classroom_members_hist_ins");
    }

    /** Rejoin epochs + invariant enforcement, both legs. */
    private static void assertSemantics(Statement st) throws SQLException {
        long owner = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v30s%s', 'v30s%s@t.example.com', 'h', 'S30', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        long student = insert(st, """
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('v30u%s', 'v30u%s@t.example.com', 'h', 'U30', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        long classroom = insert(st, """
                INSERT INTO classrooms (owner_user_id, code, name)
                VALUES (%d, 'V30S%s', 'Semantics class')
                """.formatted(owner, System.nanoTime()));

        // Epoch 1: join then leave.
        st.executeUpdate("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classroom + "," + student + ")");
        st.executeUpdate("DELETE FROM classroom_members WHERE classroom_id = "
                + classroom + " AND user_id = " + student);
        // Epoch 2: rejoin (open).
        st.executeUpdate("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classroom + "," + student + ")");

        assertThat(rows(st, """
                        SELECT count(*) FROM classroom_membership_periods
                        WHERE classroom_id = %d AND user_id = %d
                        """.formatted(classroom, student)))
                .as("two epochs after a leave/rejoin cycle")
                .containsExactly("2");
        assertThat(rows(st, """
                        SELECT count(*) FROM classroom_membership_periods
                        WHERE classroom_id = %d AND user_id = %d AND left_at IS NULL
                        """.formatted(classroom, student)))
                .as("exactly one OPEN epoch")
                .containsExactly("1");
        assertThat(rows(st, """
                        SELECT (joined_at > (SELECT MAX(left_at) FROM classroom_membership_periods
                                            WHERE classroom_id = %d AND user_id = %d AND left_at IS NOT NULL))::text
                        FROM classroom_membership_periods
                        WHERE classroom_id = %d AND user_id = %d AND left_at IS NULL
                        """.formatted(classroom, student, classroom, student)))
                .as("epoch 2 starts strictly after epoch 1 closed (skew clamp)")
                .containsExactly("true");

        // The partial unique rejects a second open epoch outright.
        assertThatThrownBy(() -> st.executeUpdate("""
                        INSERT INTO classroom_membership_periods (classroom_id, user_id, joined_at)
                        VALUES (%d, %d, now())
                        """.formatted(classroom, student)))
                .as("uk_cmp_one_open rejects a duplicate open epoch")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_cmp_one_open");
    }

    // ==================================================================
    // helpers (V29 matrix-test conventions)
    // ==================================================================

    private String createDatabase(String prefix) throws SQLException {
        String dbName = prefix + "_" + System.nanoTime();
        try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE " + dbName);
        }
        return dbName;
    }

    private String urlFor(String dbName) {
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort()
                + "/" + dbName;
    }

    private void dropDatabase(String dbName) throws SQLException {
        try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
            st.executeUpdate("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
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
