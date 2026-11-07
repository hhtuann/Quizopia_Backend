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
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class V22CredentialFoundationMigrationMatrixTest {
    @Autowired PostgreSQLContainer pg;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshAndV20UpgradePreserveLegacyAndRejectGuessedProofEpoch(boolean upgrade) throws Exception {
        String database = "credential_matrix_" + System.nanoTime();
        try {
            try (Connection c = pg.createConnection(""); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE " + database);
            }
            String url = pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + database);
            if (upgrade) {
                migrate(url, "20");
                try (Connection c = connection(url); Statement s = c.createStatement()) {
                    seed(s);
                    s.execute("INSERT INTO oauth_login_codes(id,user_id,code_hash,expires_at) SELECT gen_random_uuid(),id,repeat('a',64),now()+interval '5 minutes' FROM users WHERE username='legacy'");
                    s.execute("INSERT INTO oauth_link_requests(id,user_id,state_hash,code_verifier,nonce,expires_at) SELECT gen_random_uuid(),id,repeat('b',64),'verifier','nonce',now()+interval '5 minutes' FROM users WHERE username='legacy'");
                    s.execute("INSERT INTO identity_audit_events(user_id,event_type) SELECT id,'TEACHER_SELF_UPGRADE' FROM users WHERE username='legacy'");
                }
            }
            migrate(url, "22");
            try (Connection c = connection(url); Statement s = c.createStatement()) {
                if (!upgrade) seed(s);
                try (ResultSet r = s.executeQuery("SELECT local_credential_state,password_hash,token_version,email FROM users WHERE username='legacy'")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString(1)).isEqualTo("UNKNOWN");
                    assertThat(r.getString(2)).isEqualTo("legacy-hash");
                    assertThat(r.getInt(3)).isEqualTo(7);
                    assertThat(r.getString(4)).isEqualTo("legacy@example.com");
                }
                for (String table : new String[]{"oauth_login_codes", "oauth_link_requests"}) {
                    try (ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
                        r.next(); assertThat(r.getInt(1)).isZero();
                    }
                    try (ResultSet r = s.executeQuery("SELECT is_nullable,column_default FROM information_schema.columns WHERE table_name='" + table + "' AND column_name='credential_epoch'")) {
                        assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("NO");
                        assertThat(r.getString(2)).isNull();
                    }
                }
                assertThatThrownBy(() -> s.execute("UPDATE users SET local_credential_state='GUESSED' WHERE username='legacy'"))
                        .hasMessageContaining("chk_users_local_credential_state");
                assertThatThrownBy(() -> s.execute("UPDATE users SET local_credential_state=NULL WHERE username='legacy'"))
                        .isInstanceOf(SQLException.class);
                for (String state : new String[]{"ENABLED", "DISABLED", "UNKNOWN"}) {
                    s.execute("UPDATE users SET local_credential_state='" + state + "' WHERE username='legacy'");
                }
                for (String event : new String[]{"TEACHER_SELF_UPGRADE", "TEACHER_SELF_UPGRADE_IDEMPOTENT", "TEACHER_SELF_UPGRADE_REACTIVATED", "PROFILE_UPDATED", "CREDENTIAL_PROOF_PROMOTED", "PASSWORD_CHANGED", "PASSWORD_RESET"}) {
                    s.execute("INSERT INTO identity_audit_events(user_id,event_type) SELECT id,'" + event + "' FROM users WHERE username='legacy'");
                }
                assertThatThrownBy(() -> s.execute("INSERT INTO identity_audit_events(user_id,event_type) SELECT id,'INVALID' FROM users WHERE username='legacy'"))
                        .hasMessageContaining("chk_identity_audit_events_type");
                assertThatThrownBy(() -> s.execute("INSERT INTO oauth_login_codes(id,user_id,code_hash,expires_at,credential_epoch) SELECT gen_random_uuid(),id,repeat('c',64),now()+interval '5 minutes',-1 FROM users WHERE username='legacy'"))
                        .hasMessageContaining("chk_oauth_login_codes_credential_epoch");
                assertThatThrownBy(() -> s.execute("INSERT INTO oauth_link_requests(id,user_id,state_hash,code_verifier,nonce,expires_at,credential_epoch) SELECT gen_random_uuid(),id,repeat('d',64),'v','n',now()+interval '5 minutes',-1 FROM users WHERE username='legacy'"))
                        .hasMessageContaining("chk_oauth_link_requests_credential_epoch");
                try (ResultSet r = s.executeQuery("SELECT count(*) FROM identity_audit_events")) {
                    r.next(); assertThat(r.getInt(1)).isEqualTo(upgrade ? 8 : 7);
                }
            }
        } finally {
            try (Connection c = pg.createConnection(""); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    private Connection connection(String url) throws SQLException {
        return DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
    }
    private void migrate(String url, String target) {
        Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration").target(target).load().migrate();
    }
    private void seed(Statement s) throws SQLException {
        s.execute("INSERT INTO users(username,email,password_hash,display_name,status,token_version,failed_login_attempts,password_changed_at) VALUES('legacy','legacy@example.com','legacy-hash','Legacy','ACTIVE',7,0,now())");
    }
}
