package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.SystemAdminResultAccessPolicy;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class SystemAdminResultAccessPolicyIntegrationTests {
    @Autowired SystemAdminResultAccessPolicy policy;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    private long user() {
        String tag = UUID.randomUUID().toString();
        return jdbc.queryForObject("INSERT INTO users(username,email,password_hash,display_name) "
                + "VALUES (?,?,'test-hash','audit-test') RETURNING id", Long.class, tag, tag + "@test.local");
    }

    private void admin(long user, Instant expiry) {
        jdbc.update("INSERT INTO user_roles(user_id,role_id,expires_at) "
                + "SELECT ?,id,? FROM roles WHERE code='SYSTEM_ADMIN'", user,
                expiry == null ? null : java.sql.Timestamp.from(expiry));
    }

    @Test
    void activeDbAdminGrantIsAuditedAndRevocationIsImmediate(CapturedOutput output) {
        clock.setInstant(Instant.parse("2026-07-06T08:00:00Z"));
        long user = user();
        admin(user, null);
        assertThat(policy.grantRead(user, "ATTEMPT_RESULT", 123L)).isTrue();
        assertThat(output).contains("event=SYSTEM_ADMIN_RESULT_READ_GRANTED actorUserId=" + user
                + " resourceType=ATTEMPT_RESULT resourceId=123 at=2026-07-06T08:00:00Z");
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", user);
        assertThat(policy.grantRead(user, "SESSION_RESULTS", 456L)).isFalse();
        assertThat(output).doesNotContain("resourceId=456");
    }

    @Test
    void expiryUsesApplicationClockAndExactBoundaryDenies(CapturedOutput output) {
        Instant now = Instant.parse("2035-01-01T00:00:00Z");
        clock.setInstant(now);
        long user = user();
        admin(user, now);
        assertThat(policy.grantRead(user, "SESSION_RESULTS", 987L)).isFalse();
        assertThat(output).doesNotContain("resourceId=987");
        clock.setInstant(now.minusSeconds(1));
        assertThat(policy.grantRead(user, "SESSION_RESULTS", 987L)).isTrue();
    }

    @Test
    void disabledAccountAndOrdinaryUserCannotUseBreakGlass(CapturedOutput output) {
        long user = user();
        assertThat(policy.grantRead(user, "SESSION_RESULTS", 654L)).isFalse();
        admin(user, null);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", user);
        assertThat(policy.grantRead(user, "SESSION_RESULTS", 654L)).isFalse();
        assertThat(output).doesNotContain("resourceId=654");
    }
}
