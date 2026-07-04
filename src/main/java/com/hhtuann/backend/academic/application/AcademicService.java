package com.hhtuann.backend.academic.application;

import com.hhtuann.backend.academic.domain.model.AcademicStatus;
import com.hhtuann.backend.academic.domain.model.Subject;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.dto.SubjectListResponse;
import com.hhtuann.backend.academic.dto.SubjectView;
import com.hhtuann.backend.academic.exception.AcademicErrorCode;
import com.hhtuann.backend.academic.exception.AcademicException;
import com.hhtuann.backend.academic.repository.SubjectRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.identity.repository.RolePermissionRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Application service for the academic API. Enforces SUBJECT_READ permission,
 * active teacher role and school scope at the service layer (deny by default),
 * mirroring {@code QuestionBankService}. No {@code @PreAuthorize} — ownership
 * and school-scope are checked here, like the question-bank flow.
 */
@Service
public class AcademicService {

    /** Permission required to list subjects (granted to TEACHER + ACADEMIC_ADMIN in V3 seed). */
    static final String SUBJECT_READ = "SUBJECT_READ";

    private final TeacherProfileRepository teacherProfileRepository;
    private final SubjectRepository subjectRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final Clock clock;

    public AcademicService(TeacherProfileRepository teacherProfileRepository,
                           SubjectRepository subjectRepository,
                           RolePermissionRepository rolePermissionRepository,
                           UserRoleRepository userRoleRepository,
                           Clock clock) {
        this.teacherProfileRepository = teacherProfileRepository;
        this.subjectRepository = subjectRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.clock = clock;
    }

    /**
     * List ACTIVE subjects in the caller's school, optionally narrowed by a
     * case-insensitive name/code search and a grade-level filter.
     */
    @Transactional(readOnly = true)
    public SubjectListResponse listSubjectsForCaller(Long userId, String search, Long gradeLevelId) {
        requirePermission(userId, SUBJECT_READ);
        TeacherProfile profile = resolveTeacherProfile(userId);
        Long schoolId = profile.getSchoolId();

        List<Subject> subjects = subjectRepository
                .findBySchoolIdAndStatusOrderByGradeLevelIdAscNameAsc(schoolId, AcademicStatus.ACTIVE);

        String query = (search == null || search.isBlank()) ? null : search.toLowerCase();
        List<SubjectView> items = subjects.stream()
                .filter(s -> gradeLevelId == null || gradeLevelId.equals(s.getGradeLevelId()))
                .filter(s -> query == null
                        || s.getName().toLowerCase().contains(query)
                        || s.getCode().toLowerCase().contains(query))
                .map(s -> new SubjectView(s.getId(), s.getCode(), s.getName(), s.getGradeLevelId()))
                .toList();

        return new SubjectListResponse(items);
    }

    private void requirePermission(Long userId, String permissionCode) {
        List<String> permissions = rolePermissionRepository
                .findEffectivePermissionCodesByUserId(userId, Instant.now(clock));
        if (!permissions.contains(permissionCode)) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_ACCESS_DENIED);
        }
    }

    private TeacherProfile resolveTeacherProfile(Long userId) {
        return teacherProfileRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    List<String> roles = userRoleRepository
                            .findActiveRoleCodesByUserId(userId, Instant.now(clock));
                    if (roles.contains("TEACHER")) {
                        return new AcademicException(AcademicErrorCode.ACADEMIC_TEACHER_PROFILE_NOT_FOUND);
                    }
                    return new AcademicException(AcademicErrorCode.ACADEMIC_ACCESS_DENIED);
                });
    }
}
