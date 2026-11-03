package com.quizopia.backend.authentication.application;

import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.IdentityAuditEvent;
import com.quizopia.backend.identity.domain.model.Role;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
import com.quizopia.backend.identity.repository.IdentityAuditEventRepository;
import com.quizopia.backend.identity.repository.RoleRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Teacher self-upgrade (Identity-M2, System issue #14 / ADR-002 §8).
 *
 * <p>Business contract: any authenticated, ACTIVE user with a verified email
 * can grant themselves the TEACHER role immediately after an explicit
 * confirmation — no administrator approval. STUDENT (and every other role)
 * is kept; no second user is created; nothing else changes.
 *
 * <p>Security properties: the endpoint accepts no role parameter (only
 * TEACHER can ever be granted this way — never ACADEMIC_ADMIN or
 * SYSTEM_ADMIN); the grant is a single user_roles insert whose primary key
 * makes duplicates impossible, so double clicks and concurrent requests
 * converge on exactly one assignment (race losers adopt the winner's row);
 * every attempt is audited without secrets; and because
 * {@code QuizopiaJwtAuthenticationConverter} reloads effective authorities
 * from the database on every request, the new role applies immediately to
 * existing access tokens — no logout/login, no token reissue.
 */
@Service
public class TeacherSelfUpgradeService {

    private static final Logger log = LoggerFactory.getLogger(TeacherSelfUpgradeService.class);
    static final String TEACHER_ROLE_CODE = "TEACHER";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final IdentityAuditEventRepository auditRepository;
    private final Clock clock;

    public TeacherSelfUpgradeService(UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository,
            IdentityAuditEventRepository auditRepository,
            Clock clock) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    /**
     * Grants TEACHER to the given user if absent. Idempotent: a user who
     * already has TEACHER gets a 200 with their current roles and a distinct
     * audit event.
     *
     * @return the effective role codes after the upgrade
     */
    @Transactional
    public List<String> upgradeToTeacher(Long userId, String ip, String userAgent) {
        Instant now = Instant.now(clock);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID));

        if (user.getStatus() == UserStatus.DISABLED) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (user.getStatus() == UserStatus.PENDING) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_PENDING);
        }
        if (user.getEmailVerifiedAt() == null) {
            throw new AuthenticationException(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }

        List<String> rolesBefore = userRoleRepository.findActiveRoleCodesByUserId(userId, now);

        boolean alreadyTeacher = rolesBefore.contains(TEACHER_ROLE_CODE);
        if (!alreadyTeacher) {
            Role teacher = roleRepository.findByCode(TEACHER_ROLE_CODE)
                    .orElseThrow(() -> new IllegalStateException(
                            "Required role '" + TEACHER_ROLE_CODE + "' is not seeded; check Flyway V3"));
            // Insert-if-absent: a concurrent upgrade cannot fail this
            // transaction (no PK violation, no rollback-only state) — losers
            // simply insert 0 rows and read the winner's assignment.
            int inserted = userRoleRepository.insertAssignmentIfAbsent(userId, teacher.getId());
            alreadyTeacher = inserted == 0; // someone else granted it first
        }

        auditRepository.save(new IdentityAuditEvent(
                user,
                alreadyTeacher ? IdentityAuditEvent.TEACHER_SELF_UPGRADE_IDEMPOTENT
                        : IdentityAuditEvent.TEACHER_SELF_UPGRADE,
                ip, userAgent,
                Map.of("rolesBefore", String.join(",", rolesBefore))));

        List<String> rolesAfter = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
        if (!alreadyTeacher) {
            log.info("User {} self-upgraded to TEACHER", userId);
        }
        return rolesAfter;
    }
}
