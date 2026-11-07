package com.quizopia.backend.exam.application;


import com.quizopia.backend.classroom.domain.model.Classroom;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.common.BusinessCodes;
import com.quizopia.backend.exam.domain.model.Exam;
import com.quizopia.backend.exam.domain.model.ExamSession;
import com.quizopia.backend.exam.domain.model.ExamSessionStatus;
import com.quizopia.backend.exam.domain.model.ExamStatus;
import com.quizopia.backend.exam.domain.model.SessionVisibility;
import com.quizopia.backend.exam.dto.CreateExamSessionRequest;
import com.quizopia.backend.exam.dto.ExamSessionDetailResponse;
import com.quizopia.backend.exam.dto.ExamSessionListItem;
import com.quizopia.backend.exam.dto.SessionClassesResponse;
import com.quizopia.backend.exam.dto.SessionRosterItem;
import com.quizopia.backend.exam.dto.UpdateExamSessionRequest;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.exam.repository.ExamRepository;
import com.quizopia.backend.exam.repository.ExamSessionParticipantRepository;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.question.dto.PageResponse;
import com.quizopia.backend.realtime.event.RealtimeEventType;
import com.quizopia.backend.realtime.event.SessionRealtimeEvent;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application service for exam sessions under the one-exam-one-content model
 * (issue #2): sessions reference the exam directly ({@code examId}), can only
 * be created for PUBLISHED exams, and own their duration/window/attempts/
 * visibility settings. Legacy {@code /api/exam-sessions/*} routes and the
 * nested {@code /api/exams/{examId}/sessions/*} routes delegate here.
 */
@Service
public class ExamSessionService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORT_ALLOWLIST = Set.of("createdAt", "title", "code", "status");
    private static final String SESSION_CODE_CONFLICT = "uk_exam_sessions_creator_code_ci";

    private final ExamAuthorizationService auth;
    private final ExamRepository examRepository;
    private final ExamSessionRepository sessionRepository;
    private final ExamSessionParticipantRepository participantRepository;
    private final ClassroomRepository classroomRepository;

    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher eventPublisher;
    private final com.quizopia.backend.notification.application.NotificationService notificationService;

    public ExamSessionService(ExamAuthorizationService auth,
            ExamRepository examRepository,
            ExamSessionRepository sessionRepository,
            ExamSessionParticipantRepository participantRepository,
            ClassroomRepository classroomRepository,

            NamedParameterJdbcTemplate jdbc,
            ApplicationEventPublisher eventPublisher,
            com.quizopia.backend.notification.application.NotificationService notificationService) {
        this.auth = auth;
        this.examRepository = examRepository;
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.classroomRepository = classroomRepository;

        this.jdbc = jdbc;
        this.eventPublisher = eventPublisher;
        this.notificationService = notificationService;
    }

    // ============================================================
    // POST (nested) /api/exams/{examId}/sessions + legacy POST /api/exam-sessions
    // ============================================================

    @Transactional
    public ExamSessionDetailResponse createSession(Long userId, Long pathExamId, CreateExamSessionRequest request) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_CREATE");
        Long examId = pathExamId != null ? pathExamId : request.examId();
        if (examId == null) {
            throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
        }
        if (request.examId() != null && pathExamId != null && !request.examId().equals(pathExamId)) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_EXAM_MISMATCH);
        }

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        requireSessionManageable(userId, exam);

        if (exam.getStatus() != ExamStatus.PUBLISHED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_NOT_PUBLISHED_EXAM);
        }

        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_TIME_INVALID);
        }

        // Session-owned duration: request override, else the exam's editor
        // default, else 60 minutes.
        Integer duration = request.durationMinutes() != null
                ? request.durationMinutes()
                : (exam.getDurationMinutes() != null ? exam.getDurationMinutes() : 60);

        String code = (request.code() == null || request.code().isBlank())
                ? BusinessCodes.readableCode("ES", 8,
                        c -> sessionRepository.existsByCreatedByAndCodeIgnoreCase(userId, c))
                : request.code().trim();
        ExamSession session = new ExamSession(exam.getId(),
                code, request.title(), request.startsAt(), request.endsAt(),
                request.maxAttempts(), duration, userId);
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

        // Assign classes (only meaningful for CLASS_RESTRICTED).
        if (visibility == SessionVisibility.CLASS_RESTRICTED
                && request.classroomIds() != null && !request.classroomIds().isEmpty()) {
            List<Classroom> classrooms = validateClassroomsForOwner(userId, request.classroomIds());
            replaceSessionClasses(session.getId(), classrooms);
        }

        return buildDetailResponse(session, 0L);
    }

    // ============================================================
    // GET (nested) /api/exams/{examId}/sessions + legacy GET /api/exam-sessions/my
    // ============================================================

    @SuppressWarnings("null")
    @Transactional // not readOnly — lazy-close writes
    public PageResponse<ExamSessionListItem> listSessions(
            Long userId, String search, String statusStr, Long examId,
            int page, int size, String sort) {

        auth.requireUserWithPermission(userId, "EXAM_SESSION_READ");

        ExamSessionStatus statusFilter = parseSessionStatusFilter(statusStr);
        PageRequest pageable = safeSessionPageable(page, size, sort);
        String trimmedSearch = (search == null || search.isBlank()) ? null : search.trim();

        Instant now = Instant.now();
        sessionRepository.refreshLifecycleForCreator(userId, now);

        Page<ExamSession> sessions = sessionRepository.findCreatedByUser(
                userId, trimmedSearch, statusFilter, examId, pageable);

        return sessionListResponse(sessions, pageable);
    }

    @Transactional // not readOnly — lazy lifecycle writes
    public PageResponse<ExamSessionListItem> listSessionsForExam(
            Long userId, String search, String statusStr, Long examId,
            int page, int size, String sort) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_READ");
        Exam parent = examRepository.findById(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        if (!parent.getCreatedByUserUserId().equals(userId)) {
            throw new ExamException(ExamErrorCode.EXAM_NOT_FOUND);
        }
        ExamSessionStatus statusFilter = parseSessionStatusFilter(statusStr);
        PageRequest pageable = safeSessionPageable(page, size, sort);
        String trimmedSearch = (search == null || search.isBlank()) ? null : search.trim();
        sessionRepository.refreshLifecycleForExam(examId, Instant.now());
        return sessionListResponse(sessionRepository.findForExam(examId, trimmedSearch, statusFilter, pageable),
                pageable);
    }

    private PageResponse<ExamSessionListItem> sessionListResponse(Page<ExamSession> sessions, PageRequest pageable) {
        List<Long> sessionIds = sessions.getContent().stream().map(ExamSession::getId).toList();
        Map<Long, Long> participantCounts = batchParticipantCounts(sessionIds);

        List<ExamSessionListItem> items = sessions.getContent().stream()
                .map(s -> new ExamSessionListItem(
                        s.getId(),
                        s.getExamId(),
                        s.getCode(), s.getTitle(), s.getStatus().name(),
                        s.getStartsAt(), s.getEndsAt(), s.getMaxAttempts(),
                        participantCounts.getOrDefault(s.getId(), 0L),
                        s.getCreatedAt()))
                .toList();

        return new PageResponse<>(items, sessions.getNumber(), sessions.getSize(),
                sessions.getTotalElements(), sessions.getTotalPages(),
                pageable.getSort().toString());
    }

    // ============================================================
    // GET /api/exams/{examId}/sessions/{sessionId} (nested + legacy)
    // ============================================================

    @Transactional // lazy-close may write
    public ExamSessionDetailResponse getSessionDetail(Long userId, Long pathExamId, Long sessionId) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_READ");

        ExamSession session = resolveOwnedSession(userId, pathExamId, sessionId, true);

        Instant now = Instant.now();
        if (session.getStatus() == ExamSessionStatus.OPEN && now.isAfter(session.getEndsAt())) {
            session.close(now);
            sessionRepository.saveAndFlush(session);
            eventPublisher
                    .publishEvent(new SessionRealtimeEvent(RealtimeEventType.SESSION_CLOSED, session.getId(), now));
        }

        if (session.getStatus() == ExamSessionStatus.SCHEDULED
                && !now.isBefore(session.getStartsAt()) && !now.isAfter(session.getEndsAt())) {
            session.open(now);
            sessionRepository.saveAndFlush(session);
            eventPublisher
                    .publishEvent(new SessionRealtimeEvent(RealtimeEventType.SESSION_OPENED, session.getId(), now));
        }

        if (session.getStatus() == ExamSessionStatus.SCHEDULED && now.isAfter(session.getEndsAt())) {
            session.expire(now);
            sessionRepository.saveAndFlush(session);
            eventPublisher
                    .publishEvent(new SessionRealtimeEvent(RealtimeEventType.SESSION_CLOSED, session.getId(), now));
        }

        long participantCount = participantRepository.countByExamSessionId(sessionId);
        return buildDetailResponse(session, participantCount);
    }

    // ============================================================
    // PUT /api/exams/{examId}/sessions/{sessionId}
    // ============================================================

    @Transactional
    public ExamSessionDetailResponse updateSession(Long userId, Long pathExamId, Long sessionId,
            UpdateExamSessionRequest request) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_UPDATE");

        ExamSession session = resolveOwnedSession(userId, pathExamId, sessionId, true);

        if (!session.getVersion().equals(request.expectedVersion())) {
            throw new ExamException(ExamErrorCode.EXAM_CONCURRENT_MODIFICATION);
        }

        if (session.getStatus() != ExamSessionStatus.DRAFT && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }

        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_TIME_INVALID);
        }

        session.updateConfig(request.title(), request.startsAt(), request.endsAt(), request.maxAttempts());
        session = sessionRepository.saveAndFlush(session);

        long participantCount = participantRepository.countByExamSessionId(sessionId);
        return buildDetailResponse(session, participantCount);
    }

    // ============================================================
    // Lifecycle: schedule / open / close / cancel
    // ============================================================

    @Transactional
    public ExamSessionDetailResponse scheduleSession(Long userId, Long pathExamId, Long sessionId) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_SCHEDULE");
        ExamSession session = resolveOwnedSession(userId, pathExamId, sessionId, true);
        if (session.getStatus() == ExamSessionStatus.SCHEDULED) {
            return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
        }
        if (session.getStatus() != ExamSessionStatus.DRAFT) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        Instant now = Instant.now();
        if (!session.getEndsAt().isAfter(session.getStartsAt()) || !now.isBefore(session.getEndsAt())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_TIME_INVALID);
        }
        session.schedule();
        sessionRepository.saveAndFlush(session);
        return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
    }

    @Transactional
    public ExamSessionDetailResponse openSession(Long userId, Long pathExamId, Long sessionId) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_OPEN");
        ExamSession session = resolveOwnedSession(userId, pathExamId, sessionId, true);
        if (session.getStatus() == ExamSessionStatus.OPEN) {
            return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
        }
        if (session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        Instant now = Instant.now();
        if (now.isBefore(session.getStartsAt()) || now.isAfter(session.getEndsAt())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_TIME_INVALID);
        }
        session.open(now);
        sessionRepository.saveAndFlush(session);
        eventPublisher.publishEvent(new SessionRealtimeEvent(RealtimeEventType.SESSION_OPENED, session.getId(), now));
        return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
    }

    @Transactional
    public ExamSessionDetailResponse closeSession(Long userId, Long pathExamId, Long sessionId) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_CLOSE");
        ExamSession session = resolveOwnedSession(userId, pathExamId, sessionId, true);
        if (session.getStatus() == ExamSessionStatus.CLOSED) {
            return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
        }
        if (session.getStatus() != ExamSessionStatus.OPEN) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        Instant now = Instant.now();
        session.close(now);
        sessionRepository.saveAndFlush(session);
        eventPublisher.publishEvent(new SessionRealtimeEvent(RealtimeEventType.SESSION_CLOSED, session.getId(), now));
        notificationService.create(session.getCreatedBy(),
                com.quizopia.backend.notification.domain.model.NotificationType.SESSION_ENDED,
                "Session ended",
                "Exam session has been closed.",
                "/exams/" + session.getExamId() + "/sessions/" + sessionId);
        return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
    }

    @Transactional
    public ExamSessionDetailResponse cancelSession(Long userId, Long pathExamId, Long sessionId) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_CANCEL");
        ExamSession session = resolveOwnedSession(userId, pathExamId, sessionId, true);
        if (session.getStatus() == ExamSessionStatus.CANCELLED) {
            return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
        }
        if (session.getStatus() != ExamSessionStatus.DRAFT && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        session.cancel();
        sessionRepository.saveAndFlush(session);
        return buildDetailResponse(session, participantRepository.countByExamSessionId(sessionId));
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Loads a session, verifies the optional path examId matches, and enforces
     * ownership: the session creator must be the authenticated user. When
     * {@code forUpdate} the row is pessimistically locked.
     *
     * <p>Package-private: shared by the participant-management service so all
     * session-scoped endpoints enforce ONE policy.
     */
    ExamSession resolveOwnedSession(Long userId, Long pathExamId, Long sessionId, boolean forUpdate) {
        ExamSession session = forUpdate
                ? sessionRepository.findByIdForUpdate(sessionId)
                        .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_SESSION_NOT_FOUND))
                : sessionRepository.findById(sessionId)
                        .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_SESSION_NOT_FOUND));
        if (pathExamId != null && !pathExamId.equals(session.getExamId())) {
            // Cross-exam nested access: 404 (anti-enumeration).
            throw new ExamException(ExamErrorCode.EXAM_SESSION_EXAM_MISMATCH);
        }
        if (!session.getCreatedBy().equals(userId)) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }
        return session;
    }

    /** Session creation requires ownership of the PUBLISHED exam. */
    private void requireSessionManageable(Long userId, Exam exam) {
        if (!exam.getCreatedByUserUserId().equals(userId)) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }
    }


    private ExamSessionDetailResponse buildDetailResponse(ExamSession session, long participantCount) {
        String examCode = examRepository.findById(session.getExamId())
                .map(Exam::getCode)
                .orElse(null);
        return new ExamSessionDetailResponse(
                session.getId(),
                session.getExamId(),
                examCode,
                session.getCode(), session.getTitle(), session.getStatus().name(),
                session.getStartsAt(), session.getEndsAt(), session.getMaxAttempts(),
                session.getDurationMinutes(),
                session.getOpenedAt(), session.getClosedAt(),
                participantCount, session.getVersion(), session.getCreatedAt(),
                session.getVisibility().name());
    }

    // ============================================================
    // PUT/GET /api/exams/{examId}/sessions/{sessionId}/classes
    // ============================================================

    @Transactional
    public SessionClassesResponse assignClasses(Long userId, Long pathExamId, Long sessionId, List<Long> classroomIds) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_UPDATE");
        ExamSession session = resolveOwnedSession(userId, pathExamId, sessionId, true);
        if (session.getStatus() != ExamSessionStatus.DRAFT && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        List<Classroom> classrooms = (classroomIds == null || classroomIds.isEmpty())
                ? List.of()
                : validateClassroomsForOwner(userId, classroomIds);
        replaceSessionClasses(sessionId, classrooms);
        return listClasses(userId, pathExamId, sessionId);
    }

    @Transactional(readOnly = true)
    public SessionClassesResponse listClasses(Long userId, Long pathExamId, Long sessionId) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_READ");
        resolveOwnedSession(userId, pathExamId, sessionId, false);
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
    // GET /api/exams/{examId}/sessions/{sessionId}/roster
    // ============================================================

    /**
     * Effective roster for a session: class members (user-based) ∪ direct
     * participants. The legacy {@code studentProfileId} is included for
     * realtime-event correlation (attempt events still carry it).
     */
    @Transactional(readOnly = true)
    public List<SessionRosterItem> listRoster(Long userId, Long pathExamId, Long sessionId) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_READ");
        resolveOwnedSession(userId, pathExamId, sessionId, false);
        // Effective roster (issue #4 P0-R5): assigned-class members ∪ direct
        // participants ∪ DISTINCT actual attempt participants (user-based —
        // includes PUBLIC profile-less learners who already started).
        String sql = "SELECT DISTINCT u.id AS uid, u.display_name AS name "
                + "FROM ( "
                + "  SELECT cm.user_id FROM exam_session_classes esc "
                + "  JOIN classroom_members cm ON cm.classroom_id = esc.classroom_id "
                + "  WHERE esc.exam_session_id = :sid "
                + "  UNION "
                + "  SELECT p.user_id FROM exam_session_participants p "
                + "  WHERE p.exam_session_id = :sid "
                + "  UNION "
                + "  SELECT a.user_id FROM attempts a WHERE a.exam_session_id = :sid "
                + ") AS roster "
                + "JOIN users u ON u.id = roster.user_id "
                + "ORDER BY uid";
        return jdbc.query(sql, new MapSqlParameterSource("sid", sessionId),
                (rs, n) -> new SessionRosterItem(
                        rs.getLong("uid"),
                        null,
                        null, rs.getString("name")));
    }

    // ============================================================
    // Class-assignment helpers
    // ============================================================

    /**
     * Validates that every classroomId resolves to an ACTIVE classroom owned
     * by the calling user. Returns resolved classrooms.
     */
    @SuppressWarnings("null")
    private List<Classroom> validateClassroomsForOwner(Long userId, List<Long> classroomIds) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(classroomIds));
        Map<Long, Classroom> byId = classroomRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Classroom::getId, c -> c));
        List<Classroom> resolved = new ArrayList<>();
        for (Long id : distinct) {
            Classroom c = byId.get(id);
            if (c == null
                    || !c.getOwnerUserId().equals(userId)
                    || c.getStatus() != com.quizopia.backend.classroom.domain.model.ClassroomStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
            }
            resolved.add(c);
        }
        return resolved;
    }

    /**
     * Replace the session's assigned classes (delete old + insert new) in one tx.
     */
    private void replaceSessionClasses(Long sessionId, List<Classroom> classrooms) {
        jdbc.update("DELETE FROM exam_session_classes WHERE exam_session_id = :sid",
                new MapSqlParameterSource("sid", sessionId));
        for (Classroom c : classrooms) {
            jdbc.update(
                    "INSERT INTO exam_session_classes (exam_session_id, classroom_id) "
                            + "VALUES (:sid, :cid)",
                    new MapSqlParameterSource()
                            .addValue("sid", sessionId)
                            .addValue("cid", c.getId()));
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
