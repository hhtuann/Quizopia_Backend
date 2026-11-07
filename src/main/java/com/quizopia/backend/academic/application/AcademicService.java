package com.quizopia.backend.academic.application;

import com.quizopia.backend.academic.domain.model.AcademicStatus;
import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.dto.GradeLevelListResponse;
import com.quizopia.backend.academic.dto.GradeLevelView;
import com.quizopia.backend.academic.dto.SubjectListResponse;
import com.quizopia.backend.academic.dto.SubjectView;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Read-only application service for the GLOBAL academic catalog
 * (subjects + grade levels). Since the user-centric refactor the catalog
 * is platform-wide seed data (V24 deduplicated the school-scoped era and
 * V25 dropped the school tables); management endpoints are gone, so this
 * service only serves the catalog dropdowns.
 *
 * <p>Permissions stay DB-backed and deny-by-default (SUBJECT_READ /
 * GRADE_LEVEL_READ — both held by TEACHER); no role hierarchy, no admin
 * bypass.
 */
@Service
public class AcademicService {

    static final String SUBJECT_READ = "SUBJECT_READ";
    static final String GRADE_LEVEL_READ = "GRADE_LEVEL_READ";

    private final SubjectRepository subjectRepository;
    private final GradeLevelRepository gradeLevelRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final Clock clock;

    public AcademicService(SubjectRepository subjectRepository,
                           GradeLevelRepository gradeLevelRepository,
                           RolePermissionRepository rolePermissionRepository,
                           UserRoleRepository userRoleRepository,
                           Clock clock) {
        this.subjectRepository = subjectRepository;
        this.gradeLevelRepository = gradeLevelRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.clock = clock;
    }

    /**
     * List ACTIVE subjects of the global catalog, optionally narrowed by a
     * case-insensitive name/code search and a grade-level filter.
     */
    @Transactional(readOnly = true)
    public SubjectListResponse listSubjectsForCaller(Long userId, String search, Long gradeLevelId) {
        requirePermission(userId, SUBJECT_READ);

        List<Subject> subjects = subjectRepository
                .findByStatusOrderByGradeLevelIdAscNameAsc(AcademicStatus.ACTIVE);

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

    /**
     * List grade levels of the global catalog, ordered for stable dropdown
     * rendering.
     */
    @Transactional(readOnly = true)
    public GradeLevelListResponse listGradeLevelsForCaller(Long userId) {
        requirePermission(userId, GRADE_LEVEL_READ);

        List<GradeLevelView> items = gradeLevelRepository
                .findAllByOrderBySortOrderAscNameAsc().stream()
                .map(g -> new GradeLevelView(g.getId(), g.getCode(), g.getName(), g.getSortOrder()))
                .toList();
        return new GradeLevelListResponse(items);
    }

    private void requirePermission(Long userId, String permission) {
        Instant now = Instant.now(clock);
        List<String> perms = rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now);
        if (!perms.contains(permission)) {
            throw new com.quizopia.backend.academic.exception.AcademicException(
                    com.quizopia.backend.academic.exception.AcademicErrorCode.ACADEMIC_ACCESS_DENIED);
        }
    }
}
