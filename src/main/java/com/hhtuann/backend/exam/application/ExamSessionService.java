package com.hhtuann.backend.exam.application;

import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.classroom.domain.model.Classroom;
import com.hhtuann.backend.classroom.repository.ClassroomRepository;
import com.hhtuann.backend.common.BusinessCodes;
import com.hhtuann.backend.exam.domain.model.*;
import com.hhtuann.backend.exam.dto.*;
import com.hhtuann.backend.exam.exception.ExamErrorCode;
import com.hhtuann.backend.exam.exception.ExamException;
import com.hhtuann.backend.exam.repository.*;
import com.hhtuann.backend.question.dto.PageResponse;
import com.hhtuann.backend.realtime.event.RealtimeEventType;
import com.hhtuann.backend.realtime.event.SessionRealtimeEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Application service for the Exam Session API — 4 endpoints (A3.2-3A):
 * <ul>
 * <li>POST /api/exam-sessions — create session from a PUBLISHED version</li>
 * <li>GET /api/exam-sessions/my — paginated owner-scoped list</li>
 * <li>GET /api/exam-sessions/{sessionId} — detail (with lazy-close)</li>
 * <li>PUT /api/exam-sessions/{sessionId} — update config (DRAFT/SCHEDULED
 * only)</li>
 * </ul>
 *
 * <p>
 * Authorization (deny by default): active TEACHER role (DB) + effective
 * permission (DB)
 * + TeacherProfile. No JWT-claim authority, no role hierarchy. Lazy-close (OPEN
 * + now >
 * endsAt → CLOSED) runs on read paths (list + detail).
 */
@Service
public class ExamSessionService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORT_ALLOWLIST = Set.of("createdAt", "title", "code", "status", "startsAt",
            "endsAt");
    private static final String SESSION_CODE_CONFLICT = "uk_exam_sessions_owner_code_ci";

    private final ExamAuthorizationService auth;
    private final ExamRepository examRepository;
    private final ExamVersionRepository versionRepository;
    private final ExamSessionRepository sessionRepository;
    private final ExamSessionParticipantRepository participantRepository;
    private final ClassroomRepository classroomRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher eventPublisher;

    public ExamSessionService(ExamAuthorizationService auth,
            ExamRepository examRepository,
            ExamVersionRepository versionRepository,
            ExamSessionRepository sessionRepository,
            ExamSessionParticipantRepository participantRepository,
            ClassroomRepository classroomRepository,
            NamedParameterJdbcTemplate jdbc,
            ApplicationEventPublisher eventPublisher) {
        this.auth = auth;
        this.examRepository = examRepository;
        this.versionRepository = versionRepository;
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.classroomRepository = classroomRepository;
        this.jdbc = jdbc;
        this.eventPublisher = eventPublisher;
    }

    // ============================================================
    // POST /api/exam-sessions
    // ============================================================

    @Transactional
    public ExamSessionDetailResponse createSession(Long userId, CreateExamSessionRequest request) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_CREATE");
        Long schoolId = profile.getSchoolId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        if (!exam.getOwnerTeacherId().equals(profile.getId()) || !exam.getSchoolId().equals(schoolId)) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }

        ExamVersion version = versionRepository
                .findByExamIdAndVersionNumber(request.examId(), request.examVersionNumber())
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_VERSION_NOT_FOUND));
        if (version.getStatus() != ExamVersionStatus.PUBLISHED) {
            throw new ExamException(ExamErrorCode.EXAM_VERSION_NOT_DRAFT);
        }

        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_TIME_INVALID);
        }

        String code = (request.code() == null || request.code().isBlank())
                ? BusinessCodes.uniqueCode(20, c -> sessionRepository.existsByOwnerTeacherIdAndCodeIgnoreCase(profile.getId(), c))
                : request.code().trim();
        ExamSession session = new ExamSession(schoolId, version.getId(), profile.getId(),
                code, request.title(), request.startsAt(), request.endsAt(),
                request.maxAttempts(), userId);
        // Visibility: CLASS_RESTRICTED is the safe default (teacher must explicitly choose PUBLIC).
        SessionVisibility visibility = request.visibility() != null
                ? request.visibility()
                : SessionVisibility.CLASS_RESTRICTED;
        session.setVisibility(visibility);

        try {
            session = sessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException ex) {
            if (isSessionCodeConflict(ex)) {
                throw new ExamException(ExamErrorCode.EXAM_CODE_CONFLICT);
            }
            throw ex;
        }

        // Assign classes (only meaningful for CLASS_RESTRICTED; ignored when PUBLIC).
        if (visibility == SessionVisibility.CLASS_RESTRICTED
                && request.classroomIds() != null && !request.classroomIds().isEmpty()) {
            List<Classroom> classrooms = validateClassroomsForOwner(profile, request.classroomIds());
            replaceSessionClasses(schoolId, session.getId(), classrooms);
        }

        return buildDetailResponse(session, 0L);
    }

    // ============================================================
    // GET /api/exam-sessions/my
    // ============================================================

    @SuppressWarnings("null")
    @Transactional // not readOnly — lazy-close writes
    public PageResponse<ExamSessionListItem> listMySessions(
            Long userId, String search, String statusStr, Long examId,
            int page, int size, String sort) {

        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_READ");

        ExamSessionStatus statusFilter = parseSessionStatusFilter(statusStr);
        PageRequest pageable = safeSessionPageable(page, size, sort);
        String trimmedSearch = (search == null || search.isBlank()) ? null : search.trim();

        // Bulk lazy-close: expired OPEN → CLOSED BEFORE the filter query, so the status
        // filter
        // and pagination see the effective status. Bulk UPDATE bypasses @Version (no
        // race; no
        // managed entities). @Modifying(clearAutomatically=true) clears the PC for
        // fresh reads.
        Instant now = Instant.now();
        sessionRepository.bulkLazyCloseExpiredOpenSessions(
                profile.getId(), ExamSessionStatus.OPEN, ExamSessionStatus.CLOSED, now);

        Page<ExamSession> sessions = sessionRepository.findOwnedByTeacher(
                profile.getId(), trimmedSearch, statusFilter, examId, pageable);

        // Batch participantCount + pinned version resolution (no N+1).
        List<Long> sessionIds = sessions.getContent().stream().map(ExamSession::getId).toList();
        Map<Long, Long> participantCounts = batchParticipantCounts(sessionIds);
        Set<Long> versionIds = sessions.getContent().stream().map(ExamSession::getExamVersionId)
                .collect(Collectors.toSet());
        Map<Long, ExamVersion> versions = versionIds.isEmpty() ? Map.of()
                : versionRepository.findAllById(versionIds).stream()
                        .collect(Collectors.toMap(ExamVersion::getId, v -> v));

        List<ExamSessionListItem> items = sessions.getContent().stream()
                .map(s -> {
                    ExamVersion v = versions.get(s.getExamVersionId());
                    return new ExamSessionListItem(
                            s.getId(),
                            v != null ? v.getExamId() : null,
                            v != null ? v.getVersionNumber() : null,
                            s.getCode(), s.getTitle(), s.getStatus().name(),
                            s.getStartsAt(), s.getEndsAt(), s.getMaxAttempts(),
                            participantCounts.getOrDefault(s.getId(), 0L),
                            s.getCreatedAt());
                })
                .toList();

        return new PageResponse<>(items, sessions.getNumber(), sessions.getSize(),
                sessions.getTotalElements(), sessions.getTotalPages(),
                pageable.getSort().toString());
    }

    // ============================================================
    // GET /api/exam-sessions/{sessionId}
    // ============================================================

    @Transactional // lazy-close may write
    public ExamSessionDetailResponse getSessionDetail(Long userId, Long sessionId) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_READ");

        ExamSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_SESSION_NOT_FOUND));
        if (!session.getOwnerTeacherId().equals(profile.getId())
                || !session.getSchoolId().equals(profile.getSchoolId())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }

        // Lazy-close
        Instant now = Instant.now();
        if (session.getStatus() == ExamSessionStatus.OPEN && now.isAfter(session.getEndsAt())) {
            session.close(now);
            sessionRepository.saveAndFlush(session);
            // SESSION_CLOSED only on a real OPEN→CLOSED transition (AFTER_COMMIT). Bulk
            // list lazy-close
            // does NOT publish (documented MVP exception — no session IDs).
            eventPublisher
                    .publishEvent(new SessionRealtimeEvent(RealtimeEventType.SESSION_CLOSED, session.getId(), now));
        }

        long participantCount = participantRepository.countByExamSessionId(sessionId);
        return buildDetailResponse(session, participantCount);
    }

    // ============================================================
    // PUT /api/exam-sessions/{sessionId}
    // ============================================================

    @Transactional
    public ExamSessionDetailResponse updateSession(Long userId, Long sessionId, UpdateExamSessionRequest request) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_UPDATE");

        ExamSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_SESSION_NOT_FOUND));
        if (!session.getOwnerTeacherId().equals(profile.getId())
                || !session.getSchoolId().equals(profile.getSchoolId())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }

        // Optimistic token: expectedVersion == ExamSession.@Version.
        if (!session.getVersion().equals(request.expectedVersion())) {
            throw new ExamException(ExamErrorCode.EXAM_CONCURRENT_MODIFICATION);
        }

        // State check: only DRAFT or SCHEDULED.
        if (session.getStatus() != ExamSessionStatus.DRAFT && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }

        // Time validation (before updateConfig to throw the right error code).
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_TIME_INVALID);
        }

        // Update config (title/startsAt/endsAt/maxAttempts only — NOT
        // code/examVersion/owner/school).
        session.updateConfig(request.title(), request.startsAt(), request.endsAt(), request.maxAttempts());
        session = sessionRepository.saveAndFlush(session);

        long participantCount = participantRepository.countByExamSessionId(sessionId);
        return buildDetailResponse(session, participantCount);
    }

    // ============================================================
    // POST /api/exam-sessions/{sessionId}/schedule
    // ============================================================

    @Transactional
    public ExamSessionDetailResponse scheduleSession(Long userId, Long sessionId) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_SCHEDULE");
        ExamSession session = resolveOwnedSessionForUpdate(profile, sessionId);
        // Idempotent: already SCHEDULED → 200, no mutation, no timestamp change.
        if (session.getStatus() == ExamSessionStatus.SCHEDULED) {
            return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
        }
        if (session.getStatus() != ExamSessionStatus.DRAFT) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        Instant now = Instant.now();
        // Validate window: endsAt > startsAt (always true via invariants, defensive)
        // AND now < endsAt.
        if (!session.getEndsAt().isAfter(session.getStartsAt()) || !now.isBefore(session.getEndsAt())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_TIME_INVALID);
        }
        session.schedule(); // DRAFT → SCHEDULED (no timestamp; V8 invariant: all timestamps NULL)
        sessionRepository.saveAndFlush(session);
        return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
    }

    // ============================================================
    // POST /api/exam-sessions/{sessionId}/open
    // ============================================================

    @Transactional
    public ExamSessionDetailResponse openSession(Long userId, Long sessionId) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_OPEN");
        ExamSession session = resolveOwnedSessionForUpdate(profile, sessionId);
        // Idempotent: already OPEN → 200. Deliberately NO lazy-close here (lifecycle
        // endpoints
        // short-circuit on state); an OPEN session past endsAt stays OPEN until
        // explicit /close.
        if (session.getStatus() == ExamSessionStatus.OPEN) {
            return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
        }
        if (session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        Instant now = Instant.now();
        // No early open, no open after window: startsAt <= now <= endsAt.
        if (now.isBefore(session.getStartsAt()) || now.isAfter(session.getEndsAt())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_TIME_INVALID);
        }
        session.open(now); // SCHEDULED → OPEN + openedAt (V8 invariant: openedAt set, closedAt NULL)
        sessionRepository.saveAndFlush(session);
        eventPublisher.publishEvent(new SessionRealtimeEvent(RealtimeEventType.SESSION_OPENED, session.getId(), now));
        return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
    }

    // ============================================================
    // POST /api/exam-sessions/{sessionId}/close
    // ============================================================

    @Transactional
    public ExamSessionDetailResponse closeSession(Long userId, Long sessionId) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_CLOSE");
        ExamSession session = resolveOwnedSessionForUpdate(profile, sessionId);
        // Idempotent: already CLOSED → 200, closedAt not overwritten.
        if (session.getStatus() == ExamSessionStatus.CLOSED) {
            return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
        }
        if (session.getStatus() != ExamSessionStatus.OPEN) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        Instant now = Instant.now();
        session.close(now); // OPEN → CLOSED + closedAt (V8 invariant: openedAt & closedAt set)
        sessionRepository.saveAndFlush(session);
        eventPublisher.publishEvent(new SessionRealtimeEvent(RealtimeEventType.SESSION_CLOSED, session.getId(), now));
        return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
    }

    // ============================================================
    // POST /api/exam-sessions/{sessionId}/cancel
    // ============================================================

    @Transactional
    public ExamSessionDetailResponse cancelSession(Long userId, Long sessionId) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_CANCEL");
        ExamSession session = resolveOwnedSessionForUpdate(profile, sessionId);
        // Idempotent: already CANCELLED → 200.
        if (session.getStatus() == ExamSessionStatus.CANCELLED) {
            return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
        }
        if (session.getStatus() != ExamSessionStatus.DRAFT && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        // CANCELLED invariant (V8): openedAt IS NULL AND closedAt IS NULL.
        // DRAFT/SCHEDULED have both
        // NULL, so cancel() (status-only) satisfies the CHECK. (V8 has no cancelled_at
        // column.)
        session.cancel();
        sessionRepository.saveAndFlush(session);
        return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Lock the session row (pessimistic write) then verify owner + school. Lock
     * order: session
     * first; lifecycle ops never lock participants (they don't mutate them).
     */
    private ExamSession resolveOwnedSessionForUpdate(TeacherProfile profile, Long sessionId) {
        ExamSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_SESSION_NOT_FOUND));
        if (!session.getOwnerTeacherId().equals(profile.getId())
                || !session.getSchoolId().equals(profile.getSchoolId())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }
        return session;
    }

    private ExamSessionDetailResponse buildDetailResponse(ExamSession session, long participantCount) {
        ExamVersion version = versionRepository.findById(session.getExamVersionId()).orElse(null);
        return new ExamSessionDetailResponse(
                session.getId(),
                version != null ? version.getExamId() : null,
                version != null ? version.getVersionNumber() : null,
                session.getCode(), session.getTitle(), session.getStatus().name(),
                session.getStartsAt(), session.getEndsAt(), session.getMaxAttempts(),
                session.getOpenedAt(), session.getClosedAt(),
                participantCount, session.getVersion(), session.getCreatedAt(),
                session.getVisibility().name());
    }

    // ============================================================
    // PUT /api/exam-sessions/{sessionId}/classes — assign (replace) classes
    // ============================================================

    @Transactional
    public SessionClassesResponse assignClasses(Long userId, Long sessionId, List<Long> classroomIds) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_UPDATE");
        ExamSession session = resolveOwnedSessionForUpdate(profile, sessionId);
        if (session.getStatus() != ExamSessionStatus.DRAFT && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        List<Classroom> classrooms = (classroomIds == null || classroomIds.isEmpty())
                ? List.of()
                : validateClassroomsForOwner(profile, classroomIds);
        replaceSessionClasses(session.getSchoolId(), sessionId, classrooms);
        return listClasses(userId, sessionId);
    }

    // ============================================================
    // GET /api/exam-sessions/{sessionId}/classes — list assigned classes
    // ============================================================

    @Transactional(readOnly = true)
    public SessionClassesResponse listClasses(Long userId, Long sessionId) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_READ");
        // Read-only owner check (any state OK).
        ExamSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_SESSION_NOT_FOUND));
        if (!session.getOwnerTeacherId().equals(profile.getId())
                || !session.getSchoolId().equals(profile.getSchoolId())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }
        List<SessionClassesResponse.ClassSummary> items = jdbc.query(
                "SELECT c.id, c.code, c.name FROM classrooms c "
                        + "JOIN exam_session_classes esc ON esc.classroom_id = c.id "
                        + "WHERE esc.exam_session_id = :sid ORDER BY c.id",
                new MapSqlParameterSource("sid", sessionId),
                (rs, n) -> new SessionClassesResponse.ClassSummary(
                        rs.getLong("id"), rs.getString("code"), rs.getString("name")));
        return new SessionClassesResponse(items);
    }

    // ============================================================
    // Class-assignment helpers
    // ============================================================

    /**
     * Validate that every classroomId resolves to an ACTIVE classroom owned by the
     * caller in the same school. Returns the resolved classrooms (insert order).
     */
    @SuppressWarnings("null")
    private List<Classroom> validateClassroomsForOwner(TeacherProfile profile, List<Long> classroomIds) {
        // De-dup, preserve order.
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(classroomIds));
        Map<Long, Classroom> byId = classroomRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Classroom::getId, c -> c));
        List<Classroom> resolved = new ArrayList<>();
        for (Long id : distinct) {
            Classroom c = byId.get(id);
            if (c == null
                    || !c.getOwnerTeacherId().equals(profile.getId())
                    || !c.getSchoolId().equals(profile.getSchoolId())
                    || c.getStatus() != com.hhtuann.backend.classroom.domain.model.ClassroomStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
            }
            resolved.add(c);
        }
        return resolved;
    }

    /** Replace the session's assigned classes (delete old + insert new) in one tx. */
    private void replaceSessionClasses(Long schoolId, Long sessionId, List<Classroom> classrooms) {
        jdbc.update("DELETE FROM exam_session_classes WHERE exam_session_id = :sid",
                new MapSqlParameterSource("sid", sessionId));
        if (classrooms.isEmpty()) {
            return;
        }
        for (Classroom c : classrooms) {
            jdbc.update(
                    "INSERT INTO exam_session_classes (exam_session_id, classroom_id, school_id) "
                            + "VALUES (:sid, :cid, :school)",
                    new MapSqlParameterSource()
                            .addValue("sid", sessionId)
                            .addValue("cid", c.getId())
                            .addValue("school", schoolId));
        }
    }

    private Map<Long, Long> batchParticipantCounts(List<Long> sessionIds) {
        if (sessionIds.isEmpty())
            return Map.of();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : participantRepository.countByExamSessionIds(sessionIds)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private ExamSessionStatus parseSessionStatusFilter(String statusStr) {
        if (statusStr == null || statusStr.isBlank())
            return null;
        try {
            return ExamSessionStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
        }
    }

    private PageRequest safeSessionPageable(int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Sort safeSort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String prop = parts[0].trim();
            Sort.Direction dir = (parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc"))
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;
            if (SORT_ALLOWLIST.contains(prop)) {
                safeSort = Sort.by(dir, prop).and(Sort.by(Sort.Direction.DESC, "id"));
            } else {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
        } else {
            safeSort = safeSort.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        return PageRequest.of(safePage, safeSize, safeSort);
    }

    private static boolean isSessionCodeConflict(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.contains(SESSION_CODE_CONFLICT))
                    return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
