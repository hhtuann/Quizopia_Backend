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
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V21ProfileAuditMigrationMatrixTest {
    @Autowired PostgreSQLContainer pg;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshAndV20UpgradePreserveUsersAndAllAuditTypes(boolean upgrade) throws Exception {
        String database = "profile_matrix_" + System.nanoTime();
        try {
            try (Connection connection = pg.createConnection(""); Statement sql = connection.createStatement()) {
                sql.execute("CREATE DATABASE " + database);
            }
            String url = pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + database);
            if (upgrade) {
                migrate(url, "20");
                try (Connection connection = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
                     Statement sql = connection.createStatement()) {
                    seedUser(sql);
                    insertEvent(sql, "TEACHER_SELF_UPGRADE");
                    assertThatThrownBy(() -> insertEvent(sql, "PROFILE_UPDATED"))
                            .hasMessageContaining("chk_identity_audit_events_type");
                }
            }
            migrate(url, "21");
            try (Connection connection = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
                 Statement sql = connection.createStatement()) {
                if (!upgrade) seedUser(sql);
                try (ResultSet row = sql.executeQuery("select username,email,password_hash,display_name,token_version from users where username='profile_legacy'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("email")).isEqualTo("profile@legacy.example.com");
                    assertThat(row.getString("password_hash")).isEqualTo("legacy-hash");
                    assertThat(row.getString("display_name")).isEqualTo("Legacy Profile");
                    assertThat(row.getInt("token_version")).isEqualTo(7);
                    assertThat(row.next()).isFalse();
                }
                if (upgrade) {
                    try (ResultSet row = sql.executeQuery("select count(*) from identity_audit_events where event_type='TEACHER_SELF_UPGRADE'")) {
                        row.next();
                        assertThat(row.getInt(1)).isEqualTo(1);
                    }
                }
                for (String event : new String[]{"TEACHER_SELF_UPGRADE", "TEACHER_SELF_UPGRADE_IDEMPOTENT", "TEACHER_SELF_UPGRADE_REACTIVATED", "PROFILE_UPDATED"}) {
                    insertEvent(sql, event);
                }
                assertThatThrownBy(() -> insertEvent(sql, "UNKNOWN_EVENT"))
                        .hasMessageContaining("chk_identity_audit_events_type");
                try (ResultSet row = sql.executeQuery("select count(*) from identity_audit_events")) {
                    row.next();
                    assertThat(row.getInt(1)).isEqualTo(upgrade ? 5 : 4);
                }
            }
        } finally {
            try (Connection connection = pg.createConnection(""); Statement sql = connection.createStatement()) {
                sql.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    private void migrate(String url, String target) {
        Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration").target(target).load().migrate();
    }

    private void seedUser(Statement sql) throws Exception {
        sql.execute("""
                INSERT INTO users (username,email,password_hash,display_name,status,token_version,failed_login_attempts,password_changed_at)
                VALUES ('profile_legacy','profile@legacy.example.com','legacy-hash','Legacy Profile','ACTIVE',7,0,now())
                """);
    }

    private void insertEvent(Statement sql, String event) throws Exception {
        sql.execute("INSERT INTO identity_audit_events (user_id,event_type) SELECT id,'" + event
                + "' FROM users WHERE username='profile_legacy'");
    }
}
