package com.quizopia.backend.exam.application;

import com.quizopia.backend.classroom.domain.model.Classroom;
import com.quizopia.backend.classroom.domain.model.ClassroomStatus;
import com.quizopia.backend.classroom.dto.JoinedClassroomDetail;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.classroom.repository.ClassroomMemberRepository;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.classroom.application.ClassroomService;
import com.quizopia.backend.exam.domain.model.ExamSession;
import com.quizopia.backend.exam.domain.model.ExamSessionStatus;
import com.quizopia.backend.exam.domain.model.SessionVisibility;
import com.quizopia.backend.exam.dto.AssignableSessionItem;
import com.quizopia.backend.exam.dto.ClassroomSessionItem;
import com.quizopia.backend.exam.dto.LearnerClassroomSessionItem;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.question.dto.PageResponse;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * CLASS-M2 (issue #52): classroom-scoped session management on top of the
 * {@code exam_session_classes} association (association, never a copy).
 *
 * <h2>Teacher side (owner-only, both classroom AND session)</h2>
 * Listing is scoped to sessions assigned to the caller's classroom; the
 * ADDITIVE assign/unassign endpoints mutate exactly ONE (class, session)
 * pair — never a read-then-replace of the whole set — so assignments of
 * other classrooms survive. Lifecycle follows the existing replace-set
 * endpoint: mutation only while DRAFT/SCHEDULED, classroom ACTIVE, own
 * CLASS_RESTRICTED session; idempotent (junction composite PK + ON CONFLICT
 * DO NOTHING / row DELETE).
 *
 * <h2>Learner side (current membership only)</h2>
 * {@code /joined/{classId}} and its session list expose learner-safe
 * metadata ONLY — every lifecycle state except DRAFT (SCHEDULED / OPEN /
 * CLOSED / CANCELLED) as historical+current assignments, no attempt data,
 * no other learners, no teacher reports. Start/resume/own-result decisions
 * stay with the existing eligibility/attempt services via the
 * {@code /sessions/{sessionId}} landing route.
 */
@Service
public class ClassroomSessionService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> TEACHER_STATUS_FILTER = Set.of(
            "DRAFT", "SCHEDULED", "OPEN", "CLOSED", "CANCELLED");
    private static final Set<String> LEARNER_STATUS_FILTER = Set.of(
            "SCHEDULED", "OPEN", "CLOSED", "CANCELLED");

    private final ExamAuthorizationService auth;
    private final ClassroomService classroomService;
    private final ExamSessionService examSessionService;
    private final ClassroomRepository classroomRepository;
    private final ClassroomMemberRepository memberRepository;
    private final NamedParameterJdbcTemplate jdbc;

    public ClassroomSessionService(ExamAuthorizationService auth,
            ClassroomService classroomService,
            ExamSessionService examSessionService,
            ClassroomRepository classroomRepository,
            ClassroomMemberRepository memberRepository,
            NamedParameterJdbcTemplate jdbc) {
        this.auth = auth;
        this.classroomService = classroomService;
        this.examSessionService = examSessionService;
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.jdbc = jdbc;
    }

    // ============================================================
    // GET /api/classrooms/{classId}/sessions — teacher (owner)
    // ============================================================

    /**
     * All lifecycle states of sessions assigned to the caller's classroom
     * (owned sessions only — assignment itself is owner-validated, and the
     * created_by filter is kept as defense-in-depth). SQL paging + count,
     * stable order startsAt desc, id desc.
     */
    @Transactional(readOnly = true)
    public PageResponse<ClassroomSessionItem> listClassroomSessions(Long userId, Long classroomId,
            int page, int size, String status, String search) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_READ");
        classroomService.requireOwnedClassroom(userId, classroomId);
        requireStatusFilter(status, TEACHER_STATUS_FILTER);

        String filter = filterClause(status, search);
        MapSqlParameterSource params = baseParams(userId, classroomId, status, search);
        long total = count(
                "SELECT COUNT(*) FROM exam_session_classes esc "
                        + "JOIN exam_sessions s ON s.id = esc.exam_session_id "
                        + "JOIN exams e ON e.id = s.exam_id "
                        + "WHERE esc.classroom_id = :cid AND s.created_by = :uid" + filter,
                params);
        int[] bounds = paging(page, size, total);
        List<ClassroomSessionItem> items = jdbc.query(
                "SELECT s.id, s.code, s.title, s.status, s.visibility, s.starts_at, s.ends_at, "
                        + "s.exam_id, e.title AS exam_title "
                        + "FROM exam_session_classes esc "
                        + "JOIN exam_sessions s ON s.id = esc.exam_session_id "
                        + "JOIN exams e ON e.id = s.exam_id "
                        + "WHERE esc.classroom_id = :cid AND s.created_by = :uid" + filter
                        + " ORDER BY s.starts_at DESC, s.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", bounds[0]).addValue("offset", bounds[1]),
                (rs, n) -> new ClassroomSessionItem(
                        rs.getLong("id"), rs.getString("code"), rs.getString("title"),
                        rs.getString("status"), rs.getString("visibility"),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at").toInstant(),
                        rs.getLong("exam_id"), rs.getString("exam_title")));
        return page(items, page, bounds[0], total);
    }

    // ============================================================
    // GET /api/classrooms/{classId}/sessions/assignable — teacher (owner)
    // ============================================================

    /**
     * Own CLASS_RESTRICTED DRAFT/SCHEDULED sessions of PUBLISHED exams that
     * are NOT yet assigned to this classroom (the assign picker never offers
     * duplicates).
     */
    @Transactional(readOnly = true)
    public PageResponse<AssignableSessionItem> listAssignableSessions(Long userId, Long classroomId,
            int page, int size, String search) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_READ");
        classroomService.requireOwnedClassroom(userId, classroomId);

        String filter = searchFilter(search);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("uid", userId).addValue("cid", classroomId);
        if (search != null && !search.isBlank()) {
            params.addValue("q", likeToken(search));
        }
        String base = "FROM exam_sessions s JOIN exams e ON e.id = s.exam_id "
                + "WHERE s.created_by = :uid AND s.visibility = 'CLASS_RESTRICTED' "
                + "AND s.status IN ('DRAFT','SCHEDULED') AND e.status = 'PUBLISHED' "
                + "AND NOT EXISTS (SELECT 1 FROM exam_session_classes esc "
                + "WHERE esc.exam_session_id = s.id AND esc.classroom_id = :cid)" + filter;
        long total = count("SELECT COUNT(*) " + base, params);
        int[] bounds = paging(page, size, total);
        List<AssignableSessionItem> items = jdbc.query(
                "SELECT s.id, s.code, s.title, s.status, s.starts_at, s.ends_at, "
                        + "s.exam_id, e.title AS exam_title " + base
                        + " ORDER BY s.starts_at DESC, s.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", bounds[0]).addValue("offset", bounds[1]),
                (rs, n) -> new AssignableSessionItem(
                        rs.getLong("id"), rs.getString("code"), rs.getString("title"),
                        rs.getString("status"),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at").toInstant(),
                        rs.getLong("exam_id"), rs.getString("exam_title")));
        return page(items, page, bounds[0], total);
    }

    // ============================================================
    // POST /api/classrooms/{classId}/sessions/{sessionId} — assign (additive)
    // ============================================================

    /**
     * Atomically assigns ONE own session to ONE own ACTIVE classroom.
     * Idempotent (junction composite PK + ON CONFLICT DO NOTHING); never
     * touches other classrooms' associations. Gated exactly like the
     * existing replace-set endpoint: DRAFT/SCHEDULED session, ACTIVE
     * classroom, own CLASS_RESTRICTED session.
     */
    @Transactional
    public void assignSessionToClassroom(Long userId, Long classroomId, Long sessionId) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_UPDATE");
        Classroom classroom = classroomService.requireOwnedClassroom(userId, classroomId);
        if (classroom.getStatus() != ClassroomStatus.ACTIVE) {
            // Mirrors validateClassroomsForOwner in the replace-set flow.
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }
        ExamSession session = examSessionService.resolveOwnedSession(userId, null, sessionId, true);
        if (session.getStatus() != ExamSessionStatus.DRAFT
                && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        if (session.getVisibility() != SessionVisibility.CLASS_RESTRICTED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }
        jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id) "
                        + "VALUES (:sid, :cid) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource().addValue("sid", sessionId).addValue("cid", classroomId));
    }

    // ============================================================
    // DELETE /api/classrooms/{classId}/sessions/{sessionId} — unassign
    // ============================================================

    /**
     * Removes exactly THIS (classroom, session) association — other
     * classrooms keep theirs, attempts/results are never touched. Same
     * ownership + DRAFT/SCHEDULED lifecycle gate as assignment; idempotent
     * (deleting a non-existent association is still 204).
     */
    @Transactional
    public void unassignSessionFromClassroom(Long userId, Long classroomId, Long sessionId) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_UPDATE");
        Classroom classroom = classroomService.requireOwnedClassroom(userId, classroomId);
        // Review M2-F1: archived classrooms are read-only for members —
        // assignment mutation is denied exactly like assign (issue rule 6).
        if (classroom.getStatus() != ClassroomStatus.ACTIVE) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }
        ExamSession session = examSessionService.resolveOwnedSession(userId, null, sessionId, true);
        if (session.getStatus() != ExamSessionStatus.DRAFT
                && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        jdbc.update("DELETE FROM exam_session_classes WHERE exam_session_id = :sid "
                        + "AND classroom_id = :cid",
                new MapSqlParameterSource().addValue("sid", sessionId).addValue("cid", classroomId));
    }

    // ============================================================
    // GET /api/classrooms/joined/{classId} — learner (member-only)
    // ============================================================

    /**
     * Learner-safe detail of one JOINED classroom. Non-members (and
     * non-existent classrooms) get the same 404 — no existence oracle.
     */
    @Transactional(readOnly = true)
    public JoinedClassroomDetail getJoinedClassroom(Long userId, Long classroomId) {
        Classroom classroom = requireJoinedClassroom(userId, classroomId);
        List<Object[]> meta = jdbc.query(
                "SELECT cm.added_at, u.display_name, "
                        + "(SELECT COUNT(*) FROM exam_session_classes esc "
                        + " JOIN exam_sessions s ON s.id = esc.exam_session_id "
                        + " WHERE esc.classroom_id = :cid AND s.status <> 'DRAFT') "
                        + "FROM classroom_members cm JOIN users u ON u.id = :owner "
                        + "WHERE cm.classroom_id = :cid AND cm.user_id = :uid",
                new MapSqlParameterSource().addValue("cid", classroomId)
                        .addValue("uid", userId).addValue("owner", classroom.getOwnerUserId()),
                (rs, n) -> new Object[] { rs.getTimestamp(1).toInstant(), rs.getString(2),
                        rs.getLong(3) });
        if (meta.isEmpty()) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND);
        }
        Object[] row = meta.get(0);
        return new JoinedClassroomDetail(
                classroom.getId(), classroom.getCode(), classroom.getName(),
                classroom.getDescription(), classroom.getStatus().name(),
                (String) row[1], (java.time.Instant) row[0], (Long) row[2]);
    }

    // ============================================================
    // GET /api/classrooms/joined/{classId}/sessions — learner (member-only)
    // ============================================================

    /**
     * Assigned sessions for THIS member's classroom, every lifecycle state
     * except DRAFT. Learner-safe metadata only; starting/resuming/results
     * are decided by the existing eligibility/attempt services.
     */
    @Transactional(readOnly = true)
    public PageResponse<LearnerClassroomSessionItem> listJoinedClassroomSessions(Long userId,
            Long classroomId, int page, int size, String status) {
        requireJoinedClassroom(userId, classroomId);
        requireStatusFilter(status, LEARNER_STATUS_FILTER);

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("cid", classroomId);
        String filter = status == null || status.isBlank()
                ? " AND s.status <> 'DRAFT'"
                : " AND s.status = :status";
        if (status != null && !status.isBlank()) {
            params.addValue("status", status);
        }
        String base = "FROM exam_session_classes esc "
                + "JOIN exam_sessions s ON s.id = esc.exam_session_id "
                + "JOIN exams e ON e.id = s.exam_id "
                + "WHERE esc.classroom_id = :cid" + filter;
        long total = count("SELECT COUNT(*) " + base, params);
        int[] bounds = paging(page, size, total);
        List<LearnerClassroomSessionItem> items = jdbc.query(
                "SELECT s.id, s.code, s.title, s.status, s.starts_at, s.ends_at, "
                        + "e.title AS exam_title " + base
                        + " ORDER BY s.starts_at DESC, s.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", bounds[0]).addValue("offset", bounds[1]),
                (rs, n) -> new LearnerClassroomSessionItem(
                        rs.getLong("id"), rs.getString("code"), rs.getString("title"),
                        rs.getString("status"),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at").toInstant(),
                        rs.getString("exam_title")));
        return page(items, page, bounds[0], total);
    }

    // ============================================================
    // Internals
    // ============================================================

    /** Current-membership guard: 404 for missing classroom AND non-member. */
    private Classroom requireJoinedClassroom(Long userId, Long classroomId) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND));
        if (!memberRepository.existsByClassroomIdAndUserId(classroomId, userId)) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND);
        }
        return classroom;
    }

    private void requireStatusFilter(String status, Set<String> allowlist) {
        if (status != null && !status.isBlank() && !allowlist.contains(status)) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_VALIDATION_ERROR);
        }
    }

    private String searchFilter(String search) {
        return search == null || search.isBlank() ? "" : likeClause();
    }

    private String likeClause() {
        return " AND (LOWER(s.title) LIKE :q OR LOWER(s.code) LIKE :q OR LOWER(e.title) LIKE :q)";
    }

    private String filterClause(String status, String search) {
        String s = status != null && !status.isBlank() ? " AND s.status = :status" : "";
        return s + searchFilter(search);
    }

    private MapSqlParameterSource baseParams(Long userId, Long classroomId, String status,
            String search) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("uid", userId).addValue("cid", classroomId);
        if (status != null && !status.isBlank()) {
            params.addValue("status", status);
        }
        if (search != null && !search.isBlank()) {
            params.addValue("q", likeToken(search));
        }
        return params;
    }

    /** Lowercased contains-token with LIKE wildcards escaped. */
    private static String likeToken(String raw) {
        String escaped = raw.trim().toLowerCase()
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    /** @return {size, offset} with page/size clamped to sane bounds. */
    private static int[] paging(int page, int size, long total) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int maxPage = (int) Math.min(Integer.MAX_VALUE, total == 0 ? 0 : (total - 1) / safeSize);
        int safePage = Math.min(Math.max(page, 0), maxPage);
        return new int[] { safeSize, safePage * safeSize };
    }

    private static <T> PageResponse<T> page(List<T> items, int requestedPage, int size, long total) {
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        int pageOut = total == 0 ? 0 : Math.min(Math.max(requestedPage, 0), totalPages - 1);
        return new PageResponse<>(items, pageOut, size, total, totalPages, "startsAt,desc");
    }
}
