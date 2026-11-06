package com.quizopia.backend.schema;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V23 migration matrix (ADR-004 §"Migration and concurrent integration"):
 * fresh schema and the V20 → V23 upgrade both preserve legacy user/audit
 * data and enforce the password-reset challenge and abuse-bucket schema —
 * including the single-PENDING partial unique index, terminal-timestamp
 * consistency and the lowercase-64-hex digest/bucket-key checks.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V23PasswordResetMigrationMatrixTest {

    @Autowired PostgreSQLContainer pg;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshAndV20UpgradePreserveLegacyDataAndEnforceResetSchema(boolean upgrade) throws Exception {
        String database = "reset_matrix_" + System.nanoTime();
        try {
            try (Connection c = pg.createConnection(""); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE " + database);
            }
            String url = pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + database);
            if (upgrade) {
                migrate(url, "20");
                try (Connection c = connection(url); Statement s = c.createStatement()) {
                    seed(s);
                    // seed() writes the user AND the preserved audit row on both paths.
                }
            }
            migrate(url, "23");
            try (Connection c = connection(url); Statement s = c.createStatement()) {
                if (!upgrade) {
                    seed(s);
                }

                // Legacy user and audit data are untouched by V23.
                try (ResultSet r = s.executeQuery(
                        "SELECT email, token_version, local_credential_state FROM users WHERE username='legacy'")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString(1)).isEqualTo("legacy@example.com");
                    assertThat(r.getInt(2)).isEqualTo(7);
                    assertThat(r.getString(3)).isEqualTo("UNKNOWN");
                }
                try (ResultSet r = s.executeQuery("SELECT count(*) FROM identity_audit_events")) {
                    r.next();
                    assertThat(r.getInt(1)).isEqualTo(1);
                }

                // Challenge columns: NOT NULL except the two terminal timestamps.
                for (String column : new String[]{"user_id", "token_hash", "email_binding",
                        "email_verified_at", "credential_epoch", "status", "created_at", "expires_at"}) {
                    try (ResultSet r = s.executeQuery("SELECT is_nullable FROM information_schema.columns"
                            + " WHERE table_name='password_reset_challenges' AND column_name='" + column + "'")) {
                        assertThat(r.next()).isTrue();
                        assertThat(r.getString(1)).isEqualTo("NO");
                    }
                }
                for (String column : new String[]{"consumed_at", "superseded_at"}) {
                    try (ResultSet r = s.executeQuery("SELECT is_nullable FROM information_schema.columns"
                            + " WHERE table_name='password_reset_challenges' AND column_name='" + column + "'")) {
                        assertThat(r.next()).isTrue();
                        assertThat(r.getString(1)).isEqualTo("YES");
                    }
                }

                // A valid PENDING challenge row.
                s.execute(insertChallenge("repeat('a',64)", "'PENDING'", "NULL", "NULL"));

                // Digest must be exactly 64 lowercase hex chars (violating inserts persist nothing).
                assertThatThrownBy(() -> s.execute(insertChallenge("repeat('A',64)", "'PENDING'", "NULL", "NULL")))
                        .hasMessageContaining("chk_password_reset_challenges_hash");
                assertThatThrownBy(() -> s.execute(insertChallenge("repeat('a',63)", "'PENDING'", "NULL", "NULL")))
                        .hasMessageContaining("chk_password_reset_challenges_hash");
                assertThatThrownBy(() -> s.execute(insertChallenge("repeat('g',64)", "'PENDING'", "NULL", "NULL")))
                        .hasMessageContaining("chk_password_reset_challenges_hash");
                assertThatThrownBy(() -> s.execute(insertChallenge("repeat('0',64)", "'EXPIRED'", "NULL", "NULL")))
                        .hasMessageContaining("chk_password_reset_challenges_status");

                // Terminalize the first PENDING row: only one PENDING per user may exist.
                s.execute("UPDATE password_reset_challenges SET status = 'CONSUMED', consumed_at = created_at"
                        + " WHERE token_hash = repeat('a',64)");
                // Epoch must be non-negative (row-based update; inserts above never persisted).
                s.execute(insertChallenge("repeat('1',64)", "'PENDING'", "NULL", "NULL"));
                assertThatThrownBy(() -> s.execute("UPDATE password_reset_challenges SET credential_epoch = -1"
                        + " WHERE token_hash = repeat('1',64)"))
                        .hasMessageContaining("chk_password_reset_challenges_epoch");

                s.execute("UPDATE password_reset_challenges SET status = 'SUPERSEDED', superseded_at = created_at"
                        + " WHERE token_hash = repeat('1',64)");
                // expires_at must be strictly after created_at.
                s.execute(insertChallenge("repeat('2',64)", "'PENDING'", "NULL", "NULL"));
                assertThatThrownBy(() -> s.execute("UPDATE password_reset_challenges SET expires_at = created_at"
                        + " WHERE token_hash = repeat('2',64)"))
                        .hasMessageContaining("chk_password_reset_challenges_window");
                assertThatThrownBy(() -> s.execute("UPDATE password_reset_challenges"
                        + " SET expires_at = created_at - interval '1 minute' WHERE token_hash = repeat('2',64)"))
                        .hasMessageContaining("chk_password_reset_challenges_window");

                // Terminal timestamps: present iff the matching status, never before created_at.
                assertThatThrownBy(() -> s.execute(
                        insertChallenge("repeat('3',64)", "'PENDING'", "now()", "NULL")))
                        .hasMessageContaining("chk_password_reset_challenges_consumed");
                assertThatThrownBy(() -> s.execute(
                        insertChallenge("repeat('4',64)", "'PENDING'", "NULL", "now()")))
                        .hasMessageContaining("chk_password_reset_challenges_superseded");
                assertThatThrownBy(() -> s.execute(
                        insertChallenge("repeat('5',64)", "'CONSUMED'", "NULL", "NULL")))
                        .hasMessageContaining("chk_password_reset_challenges_consumed");
                assertThatThrownBy(() -> s.execute(
                        insertChallenge("repeat('6',64)", "'SUPERSEDED'", "NULL", "NULL")))
                        .hasMessageContaining("chk_password_reset_challenges_superseded");
                assertThatThrownBy(() -> s.execute(
                        insertChallenge("repeat('7',64)", "'CONSUMED'", "now() - interval '1 minute'", "NULL")))
                        .hasMessageContaining("chk_password_reset_challenges_consumed");
                assertThatThrownBy(() -> s.execute(
                        insertChallenge("repeat('8',64)", "'SUPERSEDED'", "NULL", "now() - interval '1 minute'")))
                        .hasMessageContaining("chk_password_reset_challenges_superseded");

                // Digest is globally unique.
                assertThatThrownBy(() -> s.execute(insertChallenge("repeat('a',64)", "'PENDING'", "NULL", "NULL")))
                        .hasMessageContaining("uk_password_reset_challenges_hash");

                // Only one PENDING challenge per user…
                assertThatThrownBy(() -> s.execute(insertChallenge("repeat('9',64)", "'PENDING'", "NULL", "NULL")))
                        .hasMessageContaining("uq_password_reset_challenges_pending_user");
                // …but a terminalized row frees the slot for its successor.
                s.execute("UPDATE password_reset_challenges SET status='SUPERSEDED', superseded_at=now()"
                        + " WHERE token_hash = repeat('2',64)");
                s.execute(insertChallenge("repeat('d',64)", "'PENDING'", "NULL", "NULL"));

                // Every binding column is NOT NULL.
                for (String column : new String[]{"user_id", "token_hash", "email_binding",
                        "email_verified_at", "credential_epoch", "status", "created_at", "expires_at"}) {
                    assertThatThrownBy(() -> s.execute("UPDATE password_reset_challenges SET " + column
                            + " = NULL WHERE token_hash = repeat('d',64)")).isInstanceOf(SQLException.class);
                }

                // FK with cascade: deleting the owner removes the challenges.
                s.execute("DELETE FROM users WHERE username='legacy'");
                try (ResultSet r = s.executeQuery("SELECT count(*) FROM password_reset_challenges")) {
                    r.next();
                    assertThat(r.getInt(1)).isZero();
                }

                // Abuse buckets: a valid row, the saturation ceiling, then every negative.
                s.execute("INSERT INTO password_reset_abuse_buckets"
                        + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
                        + " VALUES (repeat('e',64), now(), 0, NULL, now() + interval '1 hour')");
                s.execute("UPDATE password_reset_abuse_buckets SET attempts = 2147483647"
                        + " WHERE bucket_key = repeat('e',64)");
                assertThatThrownBy(() -> s.execute("INSERT INTO password_reset_abuse_buckets"
                        + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
                        + " VALUES (repeat('E',64), now(), 0, NULL, now() + interval '1 hour')"))
                        .hasMessageContaining("chk_password_reset_abuse_buckets_key");
                assertThatThrownBy(() -> s.execute("INSERT INTO password_reset_abuse_buckets"
                        + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
                        + " VALUES (repeat('e',63), now(), 0, NULL, now() + interval '1 hour')"))
                        .hasMessageContaining("chk_password_reset_abuse_buckets_key");
                assertThatThrownBy(() -> s.execute("INSERT INTO password_reset_abuse_buckets"
                        + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
                        + " VALUES (repeat('g',64), now(), 0, NULL, now() + interval '1 hour')"))
                        .hasMessageContaining("chk_password_reset_abuse_buckets_key");
                assertThatThrownBy(() -> s.execute("INSERT INTO password_reset_abuse_buckets"
                        + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
                        + " VALUES (repeat('f',64), now(), -1, NULL, now() + interval '1 hour')"))
                        .hasMessageContaining("chk_password_reset_abuse_buckets_attempts");
                assertThatThrownBy(() -> s.execute("INSERT INTO password_reset_abuse_buckets"
                        + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
                        + " VALUES (repeat('f',64), now(), 2147483648, NULL, now() + interval '1 hour')"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> s.execute("INSERT INTO password_reset_abuse_buckets"
                        + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
                        + " VALUES (repeat('f',64), now(), 0, NULL, now())"))
                        .hasMessageContaining("chk_password_reset_abuse_buckets_window");
                assertThatThrownBy(() -> s.execute("INSERT INTO password_reset_abuse_buckets"
                        + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
                        + " VALUES (repeat('f',64), now(), 0, NULL, now() - interval '1 second')"))
                        .hasMessageContaining("chk_password_reset_abuse_buckets_window");
                assertThatThrownBy(() -> s.execute("INSERT INTO password_reset_abuse_buckets"
                        + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
                        + " VALUES (repeat('e',64), now(), 0, NULL, now() + interval '1 hour')"))
                        .hasMessageContaining("password_reset_abuse_buckets_pkey");
                // last_admitted_at stays nullable.
                s.execute("UPDATE password_reset_abuse_buckets SET last_admitted_at = now()"
                        + " WHERE bucket_key = repeat('e',64)");
                s.execute("UPDATE password_reset_abuse_buckets SET last_admitted_at = NULL"
                        + " WHERE bucket_key = repeat('e',64)");
            }
        } finally {
            try (Connection c = pg.createConnection(""); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    /** Inserts a challenge for the legacy user with the given digest and terminal timestamps. */
    private static String insertChallenge(String hash, String status, String consumedAt, String supersededAt) {
        return "INSERT INTO password_reset_challenges"
                + " (user_id, token_hash, email_binding, email_verified_at, credential_epoch,"
                + "  status, created_at, expires_at, consumed_at, superseded_at)"
                + " SELECT id, " + hash + ", 'legacy@example.com', now(), 0,"
                + " " + status + ", now(), now() + interval '15 minutes', " + consumedAt + ", " + supersededAt
                + " FROM users WHERE username='legacy'";
    }

    private Connection connection(String url) throws SQLException {
        return DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
    }

    private void migrate(String url, String target) {
        Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration").target(target).load().migrate();
    }

    private void seed(Statement s) throws SQLException {
        s.execute("INSERT INTO users(username,email,password_hash,display_name,status,token_version,"
                + " failed_login_attempts,password_changed_at)"
                + " VALUES('legacy','legacy@example.com','legacy-hash','Legacy','ACTIVE',7,0,now())");
        s.execute("INSERT INTO identity_audit_events(user_id, event_type, created_at)"
                + " SELECT id, 'TEACHER_SELF_UPGRADE', now() FROM users WHERE username='legacy'");
    }
}
