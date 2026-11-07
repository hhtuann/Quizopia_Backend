package com.quizopia.backend.classroom.application;

import com.quizopia.backend.classroom.dto.StudentSearchResponse;
import com.quizopia.backend.classroom.dto.StudentSearchResponse.StudentSearchResult;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * TEACHER-gated learner search for the classroom member-add flow
 * ({@code GET /api/students/search?q=}). Returns platform users holding the
 * ACTIVE STUDENT role, matched by display name / username prefix-substring,
 * capped at 20 rows. Replaces the legacy school-scoped profile search that
 * was decommissioned with the organization architecture.
 *
 * <p>Anti-enumeration: a blank/too-short query returns an empty list (no
 * full-directory listing); only TEACHERs with CLASSROOM_MEMBER_ADD may call.
 */
@Service
public class StudentSearchService {

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_RESULTS = 20;

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final Clock clock;

    public StudentSearchService(UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            Clock clock) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StudentSearchResponse searchStudents(Long userId, String q) {
        Instant now = Instant.now(clock);
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
        if (!roles.contains("TEACHER")) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
        }
        List<String> perms = rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now);
        if (!perms.contains("CLASSROOM_MEMBER_ADD")) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
        }

        String query = q == null ? "" : q.trim();
        if (query.length() < MIN_QUERY_LENGTH) {
            return new StudentSearchResponse(List.of());
        }
        String pattern = "%" + query.replace("%", "\\%").replace("_", "\\_") + "%";
        List<StudentSearchResult> items = jdbc.query(
                """
                SELECT DISTINCT u.id, u.display_name, u.username, u.email
                FROM users u
                JOIN user_roles ur ON ur.user_id = u.id
                JOIN roles r ON r.id = ur.role_id
                WHERE r.code = 'STUDENT'
                  AND (ur.expires_at IS NULL OR ur.expires_at > now())
                  AND (LOWER(u.display_name) LIKE LOWER(?) OR LOWER(u.username) LIKE LOWER(?))
                ORDER BY u.display_name ASC
                LIMIT ?
                """,
                (rs, n) -> new StudentSearchResult(
                        rs.getLong("id"),
                        rs.getString("display_name"),
                        rs.getString("username"),
                        rs.getString("email")),
                pattern, pattern, MAX_RESULTS);
        return new StudentSearchResponse(items);
    }
}
