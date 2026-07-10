package com.hhtuann.backend.academic.application;

import com.hhtuann.backend.academic.domain.model.AcademicStatus;
import com.hhtuann.backend.academic.domain.model.GradeLevel;
import com.hhtuann.backend.academic.domain.model.School;
import com.hhtuann.backend.academic.domain.model.Subject;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.dto.CreateSchoolRequest;
import com.hhtuann.backend.academic.dto.CreateSubjectRequest;
import com.hhtuann.backend.academic.dto.GradeLevelListResponse;
import com.hhtuann.backend.academic.dto.GradeLevelView;
import com.hhtuann.backend.academic.dto.SchoolListResponse;
import com.hhtuann.backend.academic.dto.SchoolResponse;
import com.hhtuann.backend.academic.dto.SubjectListResponse;
import com.hhtuann.backend.academic.dto.SubjectResponse;
import com.hhtuann.backend.academic.dto.SubjectView;
import com.hhtuann.backend.academic.dto.UpdateSchoolRequest;
import com.hhtuann.backend.academic.dto.UpdateSubjectRequest;
import com.hhtuann.backend.academic.dto.UpdateSubjectStatusRequest;
import com.hhtuann.backend.academic.exception.AcademicErrorCode;
import com.hhtuann.backend.academic.exception.AcademicException;
import com.hhtuann.backend.academic.repository.GradeLevelRepository;
import com.hhtuann.backend.academic.repository.SchoolRepository;
import com.hhtuann.backend.academic.repository.SubjectRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.identity.repository.RolePermissionRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application service for the academic API. Enforces permissions and school
 * scope at the service layer (deny by default), mirroring
 * {@code QuestionBankService}. No {@code @PreAuthorize} — ownership and
 * school-scope are checked here.
 *
 * <p>School resolution for list queries:
 * <ul>
 *   <li>a caller with a {@link TeacherProfile} (TEACHER, or a teacher who is
 *       also an admin) is scoped to the profile's school — the {@code schoolId}
 *       query param is ignored;</li>
 *   <li>an ACADEMIC_ADMIN without a profile is scoped to the optional
 *       {@code schoolId} param, or to ALL schools when it is absent;</li>
 *   <li>a TEACHER without a profile gets {@code ACADEMIC_TEACHER_PROFILE_NOT_FOUND}.</li>
 * </ul>
 */
@Service
public class AcademicService {

    static final String SUBJECT_READ = "SUBJECT_READ";
    static final String SUBJECT_CREATE = "SUBJECT_CREATE";
    static final String SUBJECT_UPDATE = "SUBJECT_UPDATE";
    static final String SUBJECT_STATUS_UPDATE = "SUBJECT_STATUS_UPDATE";
    static final String GRADE_LEVEL_READ = "GRADE_LEVEL_READ";
    static final String SCHOOL_READ = "SCHOOL_READ";
    static final String SCHOOL_CREATE = "SCHOOL_CREATE";
    static final String SCHOOL_UPDATE = "SCHOOL_UPDATE";
    private static final String SYSTEM_ADMIN_ROLE = "SYSTEM_ADMIN";

    private final TeacherProfileRepository teacherProfileRepository;
    private final SubjectRepository subjectRepository;
    private final GradeLevelRepository gradeLevelRepository;
    private final SchoolRepository schoolRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final Clock clock;

    public AcademicService(TeacherProfileRepository teacherProfileRepository,
                           SubjectRepository subjectRepository,
                           GradeLevelRepository gradeLevelRepository,
                           SchoolRepository schoolRepository,
                           RolePermissionRepository rolePermissionRepository,
                           UserRoleRepository userRoleRepository,
                           Clock clock) {
        this.teacherProfileRepository = teacherProfileRepository;
        this.subjectRepository = subjectRepository;
        this.gradeLevelRepository = gradeLevelRepository;
        this.schoolRepository = schoolRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.clock = clock;
    }

    // ============================================================
    // GET /api/subjects
    // ============================================================

    /**
     * List ACTIVE subjects in the caller's school scope (see class javadoc),
     * optionally narrowed by a case-insensitive name/code search and a
     * grade-level filter.
     */
    @Transactional(readOnly = true)
    public SubjectListResponse listSubjectsForCaller(Long userId, Long schoolIdParam, String search, Long gradeLevelId) {
        requirePermission(userId, SUBJECT_READ);
        Long schoolId = resolveSchoolScope(userId, schoolIdParam);

        List<Subject> subjects = (schoolId != null)
                ? subjectRepository.findBySchoolIdAndStatusOrderByGradeLevelIdAscNameAsc(schoolId, AcademicStatus.ACTIVE)
                : subjectRepository.findByStatusOrderBySchoolIdAscGradeLevelIdAscNameAsc(AcademicStatus.ACTIVE);

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

    // ============================================================
    // POST /api/subjects
    // ============================================================

    /**
     * Create a subject (SUBJECT_CREATE). Validates that the school exists, the
     * grade level belongs to that school, and the code is unique within
     * {@code (school, gradeLevel)}. The unique index
     * {@code uk_subjects_school_grade_code_ci} is the last line of defence and
     * is mapped to {@code ACADEMIC_SUBJECT_CODE_CONFLICT} on a race.
     */
    @Transactional
    public SubjectResponse createSubject(Long userId, CreateSubjectRequest request) {
        requirePermission(userId, SUBJECT_CREATE);

        Long schoolId = request.schoolId();
        if (!schoolRepository.existsById(schoolId)) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_SCHOOL_NOT_FOUND);
        }
        GradeLevel gradeLevel = gradeLevelRepository.findBySchoolIdAndId(schoolId, request.gradeLevelId())
                .orElseThrow(() -> new AcademicException(AcademicErrorCode.ACADEMIC_GRADE_LEVEL_NOT_FOUND));

        subjectRepository.findBySchoolIdAndGradeLevelIdAndCodeIgnoreCase(schoolId, gradeLevel.getId(), request.code())
                .ifPresent(existing -> { throw new AcademicException(AcademicErrorCode.ACADEMIC_SUBJECT_CODE_CONFLICT); });

        Subject subject = new Subject(schoolId, gradeLevel.getId(), request.code(), request.name());
        subject.setDescription(request.description());
        try {
            subjectRepository.saveAndFlush(subject);
        } catch (DataIntegrityViolationException ex) {
            if (isSubjectCodeUniqueViolation(ex)) {
                throw new AcademicException(AcademicErrorCode.ACADEMIC_SUBJECT_CODE_CONFLICT);
            }
            throw ex;
        }
        return toResponse(subject);
    }

    // ============================================================
    // PUT /api/subjects/{id}
    // ============================================================

    /** Update the mutable non-status fields (name, description). SUBJECT_UPDATE. */
    @Transactional
    public SubjectResponse updateSubject(Long userId, Long subjectId, UpdateSubjectRequest request) {
        requirePermission(userId, SUBJECT_UPDATE);
        Subject subject = requireSubject(subjectId);
        subject.setName(request.name());
        subject.setDescription(request.description());
        subjectRepository.saveAndFlush(subject);
        return toResponse(subject);
    }

    // ============================================================
    // PUT /api/subjects/{id}/status
    // ============================================================

    /** Change subject status. SUBJECT_STATUS_UPDATE. */
    @Transactional
    public SubjectResponse updateSubjectStatus(Long userId, Long subjectId, UpdateSubjectStatusRequest request) {
        requirePermission(userId, SUBJECT_STATUS_UPDATE);
        Subject subject = requireSubject(subjectId);
        subject.setStatus(request.status());
        subjectRepository.saveAndFlush(subject);
        return toResponse(subject);
    }

    // ============================================================
    // GET /api/grade-levels
    // ============================================================

    /**
     * List grade levels in the caller's school scope (see class javadoc), ordered
     * for stable dropdown rendering. GRADE_LEVEL_READ.
     */
    @Transactional(readOnly = true)
    public GradeLevelListResponse listGradeLevelsForCaller(Long userId, Long schoolIdParam) {
        requirePermission(userId, GRADE_LEVEL_READ);
        Long schoolId = resolveSchoolScope(userId, schoolIdParam);

        List<GradeLevel> gradeLevels = (schoolId != null)
                ? gradeLevelRepository.findBySchoolIdOrderBySortOrderAscNameAsc(schoolId)
                : gradeLevelRepository.findAllByOrderBySchoolIdAscSortOrderAscNameAsc();

        List<GradeLevelView> items = gradeLevels.stream()
                .map(g -> new GradeLevelView(g.getId(), g.getCode(), g.getName(), g.getSortOrder()))
                .toList();
        return new GradeLevelListResponse(items);
    }

    // ============================================================
    // GET /api/schools
    // ============================================================

    @Transactional(readOnly = true)
    public SchoolListResponse listSchools(Long userId) {
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, Instant.now(clock));
        if (roles.contains(SYSTEM_ADMIN_ROLE)) {
            // SYSTEM_ADMIN → all schools (SCHOOL_CREATE implies full school access; V3 doesn't grant SCHOOL_READ to SYSTEM_ADMIN).
            List<School> all = schoolRepository.findAllByOrderByCodeAsc();
            return new SchoolListResponse(all.stream().map(this::toSchoolResponse).toList());
        }
        requirePermission(userId, SCHOOL_READ);
        Long schoolId = resolveSchoolScope(userId, null);
        List<School> schools = (schoolId != null)
                ? schoolRepository.findAllById(List.of(schoolId))
                : schoolRepository.findAllByOrderByCodeAsc();
        return new SchoolListResponse(schools.stream().map(this::toSchoolResponse).toList());
    }

    // ============================================================
    // POST /api/schools
    // ============================================================

    @Transactional
    public SchoolResponse createSchool(Long userId, CreateSchoolRequest request) {
        requireSystemAdmin(userId);
        if (schoolRepository.existsByCodeIgnoreCase(request.code())) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_SCHOOL_CODE_CONFLICT);
        }
        School school = new School(request.code().trim(), request.name().trim());
        if (request.address() != null) {
            school.setAddress(request.address().trim());
        }
        try {
            schoolRepository.saveAndFlush(school);
        } catch (DataIntegrityViolationException ex) {
            if (isSchoolCodeConflict(ex)) {
                throw new AcademicException(AcademicErrorCode.ACADEMIC_SCHOOL_CODE_CONFLICT);
            }
            throw ex;
        }
        return toSchoolResponse(school);
    }

    // ============================================================
    // PUT /api/schools/{id}
    // ============================================================

    @Transactional
    public SchoolResponse updateSchool(Long userId, Long schoolId, UpdateSchoolRequest request) {
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, Instant.now(clock));
        if (!roles.contains(SYSTEM_ADMIN_ROLE)) {
            requirePermission(userId, SCHOOL_UPDATE);
        }
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new AcademicException(AcademicErrorCode.ACADEMIC_SCHOOL_NOT_FOUND));
        // Non-SYSTEM_ADMIN can only update their own school.
        if (!roles.contains(SYSTEM_ADMIN_ROLE)) {
            Long scopedId = resolveSchoolScope(userId, null);
            if (scopedId == null || !scopedId.equals(schoolId)) {
                throw new AcademicException(AcademicErrorCode.ACADEMIC_ACCESS_DENIED);
            }
        }
        if (request.name() != null && !request.name().isBlank()) {
            school.setName(request.name().trim());
        }
        if (request.address() != null) {
            school.setAddress(request.address().trim());
        }
        schoolRepository.saveAndFlush(school);
        return toSchoolResponse(school);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private Subject requireSubject(Long subjectId) {
        return subjectRepository.findById(subjectId)
                .orElseThrow(() -> new AcademicException(AcademicErrorCode.ACADEMIC_SUBJECT_NOT_FOUND));
    }

    private void requirePermission(Long userId, String permissionCode) {
        List<String> permissions = rolePermissionRepository
                .findEffectivePermissionCodesByUserId(userId, Instant.now(clock));
        if (!permissions.contains(permissionCode)) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_ACCESS_DENIED);
        }
    }

    /**
     * Resolve the school scope for a list query. Returns {@code null} to mean
     * "all schools" (ACADEMIC_ADMIN without a {@code schoolId} param).
     */
    private Long resolveSchoolScope(Long userId, Long schoolIdParam) {
        Optional<TeacherProfile> profile = teacherProfileRepository.findByUserId(userId);
        if (profile.isPresent()) {
            return profile.get().getSchoolId();
        }
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, Instant.now(clock));
        if (roles.contains("ACADEMIC_ADMIN")) {
            if (schoolIdParam != null) {
                if (!schoolRepository.existsById(schoolIdParam)) {
                    throw new AcademicException(AcademicErrorCode.ACADEMIC_SCHOOL_NOT_FOUND);
                }
                return schoolIdParam;
            }
            return null; // all schools
        }
        if (roles.contains("TEACHER")) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_TEACHER_PROFILE_NOT_FOUND);
        }
        throw new AcademicException(AcademicErrorCode.ACADEMIC_ACCESS_DENIED);
    }

    private SubjectResponse toResponse(Subject s) {
        return new SubjectResponse(
                s.getId(),
                s.getSchoolId(),
                s.getGradeLevelId(),
                s.getCode(),
                s.getName(),
                s.getDescription(),
                s.getStatus().name(),
                s.getCreatedAt());
    }

    /** Detects a violation of {@code uk_subjects_school_grade_code_ci} (race fallback). */
    private static boolean isSubjectCodeUniqueViolation(DataIntegrityViolationException ex) {
        return constraintContains(ex, "uk_subjects_school_grade_code_ci");
    }

    /** Detects a violation of {@code uk_schools_code_ci} (school code race fallback). */
    private static boolean isSchoolCodeConflict(DataIntegrityViolationException ex) {
        return constraintContains(ex, "uk_schools_code_ci");
    }

    private static boolean constraintContains(Throwable ex, String fragment) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.contains(fragment)) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void requireSystemAdmin(Long userId) {
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, Instant.now(clock));
        if (!roles.contains(SYSTEM_ADMIN_ROLE)) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_ACCESS_DENIED);
        }
    }

    private SchoolResponse toSchoolResponse(School s) {
        return new SchoolResponse(
                s.getId(), s.getCode(), s.getName(), s.getAddress(),
                s.getStatus().name(), s.getCreatedAt());
    }
}
