package com.quizopia.backend.attempt.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Read-only break-glass policy. JWT roles and collapsed primary roles are never inputs. */
@Component
public class SystemAdminResultAccessPolicy {
    private static final Logger AUDIT = LoggerFactory.getLogger("quizopia.security.audit");
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SystemAdminResultAccessPolicy(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Records the authorization grant (not a claim that a response was delivered).
     * The dedicated audit logger must be retained by the deployment's log collector.
     * No result payload, token, learner name, answer or answer key is logged.
     */
    public boolean grantRead(Long userId, String resourceType, Long resourceId) {
        Instant now = clock.instant();
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                JOIN users u ON u.id = ur.user_id
                WHERE ur.user_id = ? AND u.status = 'ACTIVE' AND r.code = 'SYSTEM_ADMIN'
                  AND (ur.expires_at IS NULL OR ur.expires_at > ?)
                """, Integer.class, userId, java.sql.Timestamp.from(now));
        if (count == null || count == 0) {
            return false;
        }
        AUDIT.info("event=SYSTEM_ADMIN_RESULT_READ_GRANTED actorUserId={} resourceType={} resourceId={} at={}",
                userId, resourceType, resourceId, now);
        return true;
    }
}
