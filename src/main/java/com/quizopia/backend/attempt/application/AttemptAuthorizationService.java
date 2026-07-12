package com.quizopia.backend.attempt.application;

import com.quizopia.backend.academic.domain.model.EnrollmentStatus;
import com.quizopia.backend.academic.domain.model.StudentProfile;
import com.quizopia.backend.academic.repository.StudentProfileRepository;
import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Shared authorization component for the attempt module. Deny-by-default:
 * active STUDENT role (DB) → effective permission (DB) → StudentProfile (ACTIVE).
 * No JWT claim authority, no role hierarchy, no SYSTEM_ADMIN bypass.
 */
@Component
public class AttemptAuthorizationService {

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final Clock clock;

    public AttemptAuthorizationService(UserRoleRepository userRoleRepository,
                                        RolePermissionRepository rolePermissionRepository,
                                        StudentProfileRepository studentProfileRepository,
                                        Clock clock) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.studentProfileRepository = studentProfileRepository;
        this.clock = clock;
    }

    /**
     * Requires the caller to have an active STUDENT role AND the specified
     * effective permission (both from the DB), AND an ACTIVE StudentProfile.
     * Returns the resolved profile.
     */
    public StudentProfile requireStudentWithPermission(Long userId, String permissionCode) {
        Instant now = Instant.now(clock);
        requireActiveStudentRole(userId, now);
        requirePermission(userId, permissionCode, now);
        return resolveStudentProfile(userId, now);
    }

    /**
     * Requires an active STUDENT role AND ALL of the given effective permissions (single DB fetch)
     * AND an ACTIVE StudentProfile. Used by detail (ATTEMPT_READ + ATTEMPT_ANSWER_READ). Deny-by-default,
     * no role hierarchy, no JWT-authority trust.
     */
    public StudentProfile requireStudentWithPermissions(Long userId, String primaryPermission,
                                                        String... additionalPermissions) {
        Instant now = Instant.now(clock);
        requireActiveStudentRole(userId, now);
        List<String> perms = rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now);
        if (!perms.contains(primaryPermission)) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
        }
        for (String p : additionalPermissions) {
            if (!perms.contains(p)) {
                throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
            }
        }
        return resolveStudentProfile(userId, now);
    }

    private void requireActiveStudentRole(Long userId, Instant now) {
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
        if (!roles.contains("STUDENT")) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
        }
    }

    private void requirePermission(Long userId, String permission, Instant now) {
        List<String> perms = rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now);
        if (!perms.contains(permission)) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
        }
    }

    private StudentProfile resolveStudentProfile(Long userId, Instant now) {
        StudentProfile profile = studentProfileRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
                    if (roles.contains("STUDENT")) {
                        return new AttemptException(AttemptErrorCode.ATTEMPT_STUDENT_PROFILE_NOT_FOUND);
                    }
                    return new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
                });
        if (profile.getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
        }
        return profile;
    }
}
