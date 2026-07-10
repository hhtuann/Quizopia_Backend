package com.hhtuann.backend.academic.application;

import com.hhtuann.backend.academic.domain.model.School;
import com.hhtuann.backend.academic.domain.model.StudentProfile;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.dto.PendingStudentItem;
import com.hhtuann.backend.academic.dto.StudentProfileResponse;
import com.hhtuann.backend.academic.dto.StudentSearchResult;
import com.hhtuann.backend.academic.dto.StudentSearchResponse;
import com.hhtuann.backend.academic.exception.AcademicErrorCode;
import com.hhtuann.backend.academic.exception.AcademicException;
import com.hhtuann.backend.academic.repository.SchoolRepository;
import com.hhtuann.backend.academic.repository.StudentProfileRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.repository.RolePermissionRepository;
import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import com.hhtuann.backend.question.dto.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Student onboarding: pending-student list, school assignment with auto-generated
 * student_code, and school-scoped student search. Mirrors AcademicService (permission
 * checks) + ClassroomService (school scope via TeacherProfile).
 */
@Service
public class StudentOnboardingService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int SEARCH_LIMIT = 20;
    private static final String ACADEMIC_ADMIN = "ACADEMIC_ADMIN";
    private static final String STUDENT_ROLE = "STUDENT";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final SchoolRepository schoolRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final com.hhtuann.backend.notification.application.NotificationService notificationService;

    public StudentOnboardingService(UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            StudentProfileRepository studentProfileRepository,
            TeacherProfileRepository teacherProfileRepository,
            SchoolRepository schoolRepository,
            NamedParameterJdbcTemplate jdbc,
            Clock clock,
            com.hhtuann.backend.notification.application.NotificationService notificationService) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.studentProfileRepository = studentProfileRepository;
        this.teacherProfileRepository = teacherProfileRepository;
        this.schoolRepository = schoolRepository;
        this.jdbc = jdbc;
        this.clock = clock;
        this.notificationService = notificationService;
    }

    // ============================================================
    // GET /api/admin/pending-students
    // ============================================================

    @Transactional(readOnly = true)
    public PageResponse<PendingStudentItem> listPendingStudents(
            Long userId, String search, int page, int size) {
        requirePermission(userId, "USER_READ");
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        int offset = safePage * safeSize;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", safeSize)
                .addValue("offset", offset);
        StringBuilder where = new StringBuilder("""
                FROM users u
                JOIN user_roles ur ON ur.user_id = u.id
                JOIN roles r ON r.id = ur.role_id AND r.code = 'STUDENT'
                LEFT JOIN student_profiles sp ON sp.user_id = u.id
                WHERE sp.id IS NULL
                  AND (ur.expires_at IS NULL OR ur.expires_at > now())
                  AND u.status = 'ACTIVE'
                """);
        if (search != null && !search.isBlank()) {
            where.append("  AND (LOWER(u.username) LIKE LOWER(:search) ")
                    .append("OR LOWER(u.email) LIKE LOWER(:search) ")
                    .append("OR LOWER(u.display_name) LIKE LOWER(:search)) ");
            params.addValue("search", "%" + search.trim() + "%");
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) " + where, params, Long.class);
        if (total == null) total = 0L;

        List<PendingStudentItem> items = jdbc.query(
                "SELECT u.id, u.username, u.email, u.display_name, u.created_at " + where
                        + "ORDER BY u.created_at DESC, u.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", safeSize).addValue("offset", offset),
                (rs, n) -> new PendingStudentItem(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getTimestamp("created_at").toInstant()));
        int totalPages = safeSize > 0 ? (int) Math.ceil((double) total / safeSize) : 0;
        return new PageResponse<>(items, safePage, safeSize, total, totalPages, "createdAt: DESC");
    }

    // ============================================================
    // POST /api/admin/students/{userId}/assign-school
    // ============================================================

    @Transactional
    public StudentProfileResponse assignStudentToSchool(Long callerId, Long targetUserId, Long schoolId) {
        requireAcademicAdmin(callerId);
        requirePermission(callerId, "STUDENT_PROFILE_CREATE");

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new AcademicException(AcademicErrorCode.ACADEMIC_SCHOOL_NOT_FOUND));

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new AcademicException(AcademicErrorCode.ACADEMIC_STUDENT_NOT_FOUND));

        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(targetUserId, Instant.now(clock));
        if (!roles.contains(STUDENT_ROLE)) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_STUDENT_NOT_FOUND);
        }
        if (studentProfileRepository.existsByUserId(targetUserId)) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_STUDENT_ALREADY_ASSIGNED);
        }

        String studentCode = generateStudentCode(school);
        StudentProfile profile = new StudentProfile(targetUserId, schoolId, studentCode);
        studentProfileRepository.saveAndFlush(profile);

        // Notify the student they've been assigned to a school.
        notificationService.create(targetUserId,
                com.hhtuann.backend.notification.domain.model.NotificationType.USER_STATUS_CHANGED,
                "School assignment approved",
                "You have been assigned to " + school.getName() + ". Your student code is " + studentCode + ".",
                "/sessions");

        return new StudentProfileResponse(
                profile.getId(), profile.getStudentCode(), profile.getSchoolId(),
                profile.getUserId(), user.getUsername(), user.getDisplayName(),
                profile.getEnrollmentStatus().name());
    }

    // ============================================================
    // GET /api/students/search
    // ============================================================

    @Transactional(readOnly = true)
    public StudentSearchResponse searchStudents(Long callerId, String query) {
        requirePermission(callerId, "STUDENT_PROFILE_READ");
        TeacherProfile profile = teacherProfileRepository.findByUserId(callerId)
                .orElseThrow(() -> new AcademicException(AcademicErrorCode.ACADEMIC_TEACHER_PROFILE_NOT_FOUND));
        Long schoolId = profile.getSchoolId();

        String q = (query == null || query.isBlank()) ? null : "%" + query.trim().toLowerCase() + "%";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("schoolId", schoolId)
                .addValue("query", q)
                .addValue("limit", SEARCH_LIMIT);
        String sql = """
                SELECT sp.id AS sp_id, sp.student_code, u.display_name, u.username, u.email
                FROM student_profiles sp
                JOIN users u ON u.id = sp.user_id
                WHERE sp.school_id = :schoolId
                  AND sp.enrollment_status = 'ACTIVE'
                """;
        if (q != null) {
            sql += "  AND (LOWER(sp.student_code) LIKE :query "
                 + "OR LOWER(u.username) LIKE :query "
                 + "OR LOWER(u.display_name) LIKE :query "
                 + "OR LOWER(u.email) LIKE :query) ";
        }
        sql += "ORDER BY sp.student_code ASC LIMIT :limit";

        List<StudentSearchResult> items = jdbc.query(sql, params,
                (rs, n) -> new StudentSearchResult(
                        rs.getLong("sp_id"),
                        rs.getString("student_code"),
                        rs.getString("display_name"),
                        rs.getString("username"),
                        rs.getString("email")));
        return new StudentSearchResponse(items);
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Atomic counter increment → student_code = STU + zero-padded counter. */
    private String generateStudentCode(School school) {
        Long counter = jdbc.queryForObject(
                "UPDATE schools SET student_counter = student_counter + 1 WHERE id = :id RETURNING student_counter",
                new MapSqlParameterSource("id", school.getId()), Long.class);
        if (counter == null) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_SCHOOL_COUNTER_FAILED);
        }
        return "STU" + String.format("%04d", counter);
    }

    private void requireAcademicAdmin(Long userId) {
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, Instant.now(clock));
        if (!roles.contains(ACADEMIC_ADMIN)) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_ACCESS_DENIED);
        }
    }

    private void requirePermission(Long userId, String permissionCode) {
        List<String> permissions = rolePermissionRepository
                .findEffectivePermissionCodesByUserId(userId, Instant.now(clock));
        if (!permissions.contains(permissionCode)) {
            throw new AcademicException(AcademicErrorCode.ACADEMIC_ACCESS_DENIED);
        }
    }
}
