package com.quizopia.backend.exam.application;

import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Shared authorization component for the exam module. Enforces deny-by-default:
 * active DB role (STUDENT/TEACHER) → effective DB permission. No JWT claim
 * authority, no role hierarchy, no admin bypass. Resource access is enforced
 * separately by user-ownership checks in the services.
 */
@Component
public class ExamAuthorizationService {

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final Clock clock;

    public ExamAuthorizationService(UserRoleRepository userRoleRepository,
                                     RolePermissionRepository rolePermissionRepository,
                                     Clock clock) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.clock = clock;
    }

    /**
     * Requires the caller to have an active TEACHER role AND the specified
     * effective permission (both read from the DB, not JWT claims).
     */
    public void requireTeacherWithPermission(Long userId, String permissionCode) {
        Instant now = Instant.now(clock);
        requireActiveTeacherRole(userId, now);
        requirePermission(userId, permissionCode, now);
    }

    /**
     * Requires the effective permission only — the caller's STUDENT/TEACHER
     * capability. Resource access is additionally guarded by user-ownership
     * checks in the services.
     */
    public void requireUserWithPermission(Long userId, String permissionCode) {
        Instant now = Instant.now(clock);
        requirePermission(userId, permissionCode, now);
    }

    private void requireActiveTeacherRole(Long userId, Instant now) {
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
        if (!roles.contains("TEACHER")) {
            throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED);
        }
    }

    private void requirePermission(Long userId, String permission, Instant now) {
        List<String> perms = rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now);
        if (!perms.contains(permission)) {
            throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED);
        }
    }
}
