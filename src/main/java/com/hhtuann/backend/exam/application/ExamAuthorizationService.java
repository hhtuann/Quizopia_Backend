package com.hhtuann.backend.exam.application;

import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.exam.exception.ExamErrorCode;
import com.hhtuann.backend.exam.exception.ExamException;
import com.hhtuann.backend.identity.repository.RolePermissionRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Shared authorization component for the exam module. Enforces deny-by-default:
 * active TEACHER role (DB) → effective permission (DB) → TeacherProfile exists.
 * No JWT claim authority, no role hierarchy, no SYSTEM_ADMIN bypass.
 */
@Component
public class ExamAuthorizationService {

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final Clock clock;

    public ExamAuthorizationService(UserRoleRepository userRoleRepository,
                                     RolePermissionRepository rolePermissionRepository,
                                     TeacherProfileRepository teacherProfileRepository,
                                     Clock clock) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.teacherProfileRepository = teacherProfileRepository;
        this.clock = clock;
    }

    /**
     * Requires the caller to have an active TEACHER role AND the specified
     * effective permission (both read from the DB, not JWT claims), AND a
     * TeacherProfile. Returns the resolved profile.
     */
    public TeacherProfile requireTeacherWithPermission(Long userId, String permissionCode) {
        Instant now = Instant.now(clock);
        requireActiveTeacherRole(userId, now);
        requirePermission(userId, permissionCode, now);
        return resolveTeacherProfile(userId, now);
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

    private TeacherProfile resolveTeacherProfile(Long userId, Instant now) {
        return teacherProfileRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
                    if (roles.contains("TEACHER")) {
                        return new ExamException(ExamErrorCode.EXAM_TEACHER_PROFILE_NOT_FOUND);
                    }
                    return new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED);
                });
    }
}
