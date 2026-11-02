package com.quizopia.backend.attempt.application;

import com.quizopia.backend.attempt.dto.SessionResultItem;
import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.grading.BestResultComparator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Day 8 teacher/admin session results — one BEST row per student, paginated,
 * with authorization.
 * Uses PostgreSQL CTE + {@code ROW_NUMBER()} window function for BEST selection
 * (matches
 * {@link BestResultComparator}). {@code COUNT(*) OVER} provides per-student
 * attempt count with no N+1.
 * Sort/filter use hardcoded SQL fragments with parameter binding; database
 * {@code LIMIT}/{@code OFFSET}.
 */
@Service
@Transactional(readOnly = true)
public class SessionResultService {

    private static final Set<String> SORT_ALLOWLIST = Set.of("percentage", "score", "submittedAt", "studentName",
            "studentCode");
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;

    public SessionResultService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final Map<String, String> SORT_SQL = Map.of(
            "percentage", "percentage", "score", "final_score", "submittedAt", "submitted_at",
            "studentName", "display_name", "studentCode", "student_code");

    /**
     * Returns a paginated list of BEST results for a session using PostgreSQL CTE +
     * window function.
     * Authorization: teacher must own the session; SYSTEM_ADMIN/ACADEMIC_ADMIN
     * allowed; STUDENT denied.
     * No N+1 — attempt_count via COUNT(*) OVER; no per-student count query;
     * database LIMIT/OFFSET.
     */
    public SessionResultsPage getSessionResults(Long userId, String primaryRole, Long sessionId,
            int page, int size, String sort, String direction,
            String search, BigDecimal minPct, BigDecimal maxPct) {
        authorizeSessionAccess(userId, primaryRole, sessionId);
        // Strict validation (FINDING 3)
        if (page < 0)
            throw new AttemptException(AttemptErrorCode.INVALID_RESULT_QUERY);
        if (size <= 0 || size > MAX_PAGE_SIZE)
            throw new AttemptException(AttemptErrorCode.INVALID_RESULT_QUERY);
        if (direction != null && !direction.isBlank() && !"ASC".equalsIgnoreCase(direction)
                && !"DESC".equalsIgnoreCase(direction))
            throw new AttemptException(AttemptErrorCode.INVALID_RESULT_QUERY);
        if (minPct != null && minPct.compareTo(BigDecimal.ZERO) < 0)
            throw new AttemptException(AttemptErrorCode.INVALID_RESULT_QUERY);
        if (minPct != null && minPct.compareTo(new BigDecimal("100")) > 0)
            throw new AttemptException(AttemptErrorCode.INVALID_RESULT_QUERY);
        if (maxPct != null && maxPct.compareTo(BigDecimal.ZERO) < 0)
            throw new AttemptException(AttemptErrorCode.INVALID_RESULT_QUERY);
        if (maxPct != null && maxPct.compareTo(new BigDecimal("100")) > 0)
            throw new AttemptException(AttemptErrorCode.INVALID_RESULT_QUERY);
        if (minPct != null && maxPct != null && minPct.compareTo(maxPct) > 0)
            throw new AttemptException(AttemptErrorCode.INVALID_RESULT_QUERY);
        String sortField = (sort == null || sort.isBlank()) ? "percentage" : sort;
        if (!SORT_ALLOWLIST.contains(sortField))
            throw new AttemptException(AttemptErrorCode.INVALID_RESULT_QUERY);
        boolean desc = direction == null || direction.isBlank() || "DESC".equalsIgnoreCase(direction);
        String sortDir = desc ? "DESC" : "ASC";
        String sortColumn = SORT_SQL.get(sortField);
        // Build filter conditions (hardcoded SQL fragments; user values via parameter
        // binding)
        StringBuilder filterSql = new StringBuilder();
        List<Object> filterArgs = new java.util.ArrayList<>();
        filterArgs.add(sessionId);
        if (search != null && !search.isBlank()) {
            filterSql.append(" AND (LOWER(display_name) LIKE LOWER(?) OR LOWER(student_code) LIKE LOWER(?))");
            String pattern = "%" + search.replace("%", "\\%").replace("_", "\\_") + "%";
            filterArgs.add(pattern);
            filterArgs.add(pattern);
        }
        if (minPct != null) {
            filterSql.append(" AND percentage >= ?");
            filterArgs.add(minPct);
        }
        if (maxPct != null) {
            filterSql.append(" AND percentage <= ?");
            filterArgs.add(maxPct);
        }

        // CTE with ROW_NUMBER for BEST + COUNT(*) OVER for attempt_count — no N+1
        // User-based partition (issue #4 P0-1); student_profile is optional
        // provenance (public users have none).
        String baseCte = """
                SELECT a.user_id AS student_id, a.id AS attempt_id, a.submitted_at,
                       g.final_score, g.max_score, g.percentage, g.status AS grade_status, g.graded_at,
                       sp.student_code, u.display_name,
                       ROW_NUMBER() OVER (
                           PARTITION BY a.user_id
                           ORDER BY g.percentage DESC NULLS LAST, g.final_score DESC, a.submitted_at ASC, a.id ASC
                       ) AS rn,
                       COUNT(*) OVER (PARTITION BY a.user_id) AS attempt_count
                FROM attempts a
                JOIN grades g ON g.attempt_id = a.id
                JOIN users u ON u.id = a.user_id
                LEFT JOIN student_profiles sp ON sp.id = a.student_profile_id
                WHERE a.exam_session_id = ? AND a.status IN ('SUBMITTED','GRADED')
                """;
        String filters = filterSql.toString();

        // Count total BEST results matching filters
        String countSql = "SELECT count(*) FROM (" + baseCte + ") ranked WHERE rn = 1" + filters;
        Integer total = jdbc.queryForObject(countSql, Integer.class, filterArgs.toArray());
        int totalCount = total != null ? total : 0;

        // Page query with sort + LIMIT/OFFSET at database level
        List<Object> pageArgs = new java.util.ArrayList<>(filterArgs);
        pageArgs.add(size);
        pageArgs.add(page * size);
        String pageSql = "SELECT * FROM (" + baseCte + ") ranked WHERE rn = 1" + filters
                + " ORDER BY " + sortColumn + " " + sortDir
                + " NULLS LAST, submitted_at ASC NULLS LAST, student_id ASC LIMIT ? OFFSET ?";
        List<SessionResultItem> items = jdbc.query(pageSql, (rs, n) -> new SessionResultItem(
                rs.getLong("student_id"), rs.getString("student_code"), rs.getString("display_name"),
                rs.getLong("attempt_id"), rs.getInt("attempt_count"),
                rs.getTimestamp("submitted_at") != null ? rs.getTimestamp("submitted_at").toInstant() : null,
                rs.getBigDecimal("final_score"), rs.getBigDecimal("max_score"),
                rs.getBigDecimal("percentage"), rs.getString("grade_status")),
                pageArgs.toArray());
        int totalPages = totalCount > 0 ? (totalCount + size - 1) / size : 0;
        return new SessionResultsPage(items, page, size, totalCount, totalPages, sortField, sortDir);
    }

    /** Paginated session results response. */
    public record SessionResultsPage(
            List<SessionResultItem> items, int page, int size, long totalElements,
            int totalPages, String sort, String direction) {
    }

    /**
     * Returns ALL BEST rows for the session (no pagination/filter) — for
     * statistics/export reuse.
     */
    public List<SessionResultItem> getAllBestResults(Long userId, String primaryRole, Long sessionId) {
        authorizeSessionAccess(userId, primaryRole, sessionId);
        return queryBestResults(sessionId);
    }

    /**
     * Resource-based authorization (issue #4 P0-R3) — the resolved role no
     * longer grants blanket access:
     * <ol>
     *   <li>SYSTEM_ADMIN — explicit, DB-verified platform-operator break-glass
     *       (audited policy; never a caller-supplied role string);</li>
     *   <li>everyone else (TEACHER, ACADEMIC_ADMIN, STUDENT, unsupported/null)
     *       — organizer check: session creator with valid workspace access, or
     *       an ACTIVE OWNER/ADMIN member of the exam's workspace. An
     *       ACADEMIC_ADMIN of a FOREIGN organization (or with a revoked or
     *       absent membership) is denied.</li>
     * </ol>
     */
    void authorizeSessionAccess(Long userId, String primaryRole, Long sessionId) {
        if (isSystemAdmin(userId)) {
            return;
        }
        Integer owns = jdbc.queryForObject(
                "SELECT count(*) FROM exam_sessions s "
                        + "JOIN exams e ON e.id = s.exam_id "
                        + "JOIN workspaces w ON w.id = e.workspace_id "
                        + "LEFT JOIN workspace_memberships wm ON wm.workspace_id = e.workspace_id "
                        + "  AND wm.user_id = ? AND wm.status = 'ACTIVE' "
                        + "WHERE s.id = ? AND ("
                        + "  (s.created_by = ? AND (w.workspace_type = 'PERSONAL' OR wm.id IS NOT NULL))"
                        + "  OR wm.role IN ('OWNER','ADMIN'))",
                Integer.class, userId, sessionId, userId);
        if (owns == null || owns == 0) {
            throw new AttemptException(AttemptErrorCode.SESSION_RESULTS_ACCESS_DENIED);
        }
    }

    /** DB-verified active SYSTEM_ADMIN role (not caller-supplied). */
    private boolean isSystemAdmin(Long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                        + "WHERE ur.user_id = ? AND r.code = 'SYSTEM_ADMIN' "
                        + "AND (ur.expires_at IS NULL OR ur.expires_at > now())",
                Integer.class, userId);
        return count != null && count > 0;
    }

    /**
     * Package-private raw BEST query — shared by results, statistics, and export.
     * No N+1 (CTE + window).
     */
    List<SessionResultItem> queryBestResults(Long sessionId) {
        return jdbc.query("""
                SELECT * FROM (
                    SELECT a.user_id AS student_id, a.id AS attempt_id, a.submitted_at,
                           g.final_score, g.max_score, g.percentage, g.status AS grade_status, g.graded_at,
                           sp.student_code, u.display_name,
                           ROW_NUMBER() OVER (
                               PARTITION BY a.user_id
                               ORDER BY g.percentage DESC NULLS LAST, g.final_score DESC, a.submitted_at ASC, a.id ASC
                           ) AS rn,
                           COUNT(*) OVER (PARTITION BY a.user_id) AS attempt_count
                    FROM attempts a
                    JOIN grades g ON g.attempt_id = a.id
                    JOIN users u ON u.id = a.user_id
                    LEFT JOIN student_profiles sp ON sp.id = a.student_profile_id
                    WHERE a.exam_session_id = ? AND a.status IN ('SUBMITTED','GRADED')
                ) ranked WHERE rn = 1
                ORDER BY percentage DESC NULLS LAST, submitted_at ASC, student_id ASC
                """, (rs, n) -> new SessionResultItem(
                rs.getLong("student_id"),
                rs.getString("student_code"),
                rs.getString("display_name"),
                rs.getLong("attempt_id"),
                rs.getInt("attempt_count"),
                rs.getTimestamp("submitted_at") != null ? rs.getTimestamp("submitted_at").toInstant() : null,
                rs.getBigDecimal("final_score"),
                rs.getBigDecimal("max_score"),
                rs.getBigDecimal("percentage"),
                rs.getString("grade_status")),
                sessionId);
    }
}
