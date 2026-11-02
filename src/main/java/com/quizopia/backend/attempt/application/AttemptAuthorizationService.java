package com.quizopia.backend.attempt.application;

import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Shared authorization component for the attempt module. Deny-by-default:
 * the caller must hold the required EFFECTIVE PERMISSION (resolved from the
 * DB — no JWT-claim authority, no role hierarchy, no SYSTEM_ADMIN bypass).
 *
 * <p>User-based model (issue #4 P0-1): permission possession IS the attempt
 * capability — a public user (no school, no StudentProfile) with the learner
 * permissions may discover/start/submit attempts; legacy StudentProfile is
 * optional provenance only and no longer gates access. The STUDENT role
 * carries the learner permissions natively; since V16 the TEACHER role also
 * carries ATTEMPT_START/ATTEMPT_ANSWER_SAVE/ATTEMPT_SUBMIT so one account
 * can act as creator and learner (dual capability, server-side).
 */
@Component
public class AttemptAuthorizationService {

    private final RolePermissionRepository rolePermissionRepository;
    private final Clock clock;

    public AttemptAuthorizationService(RolePermissionRepository rolePermissionRepository,
                                       Clock clock) {
        this.rolePermissionRepository = rolePermissionRepository;
        this.clock = clock;
    }

    /**
     * Requires the caller to hold the specified effective attempt permission.
     * Any user with the permission can participate (STUDENT-role users and,
     * since V16, TEACHER-role users on the three learner permissions).
     */
    public void requireLearnerWithPermission(Long userId, String permissionCode) {
        List<String> perms = rolePermissionRepository
                .findEffectivePermissionCodesByUserId(userId, Instant.now(clock));
        if (!perms.contains(permissionCode)) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
        }
    }

    /**
     * Requires ALL of the given effective permissions (single DB fetch).
     * Used by detail (ATTEMPT_READ + ATTEMPT_ANSWER_READ).
     */
    public void requireLearnerWithPermissions(Long userId, String primaryPermission,
                                              String... additionalPermissions) {
        List<String> perms = rolePermissionRepository
                .findEffectivePermissionCodesByUserId(userId, Instant.now(clock));
        if (!perms.contains(primaryPermission)) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
        }
        for (String p : additionalPermissions) {
            if (!perms.contains(p)) {
                throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
            }
        }
    }
}
