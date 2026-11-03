package com.quizopia.backend.exam.application;

import com.quizopia.backend.academic.domain.model.StudentProfile;
import com.quizopia.backend.academic.repository.StudentProfileRepository;
import com.quizopia.backend.exam.domain.model.*;
import com.quizopia.backend.exam.dto.*;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.exam.repository.*;
import com.quizopia.backend.question.dto.PageResponse;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Application service for the Exam Session Participant API — 4 endpoints:
 * <ul>
 * <li>POST /api/exam-sessions/{sessionId}/participants — bulk add (partial
 * classification)</li>
 * <li>GET /api/exam-sessions/{sessionId}/participants — paginated list</li>
 * <li>POST .../participants/{participantId}/block — block participant</li>
 * <li>POST .../participants/{participantId}/unblock — unblock participant</li>
 * </ul>
 *
 * <p>Authorization (issue #4 P0-R6): all four endpoints enforce the SAME
 * workspace/session resource policy as {@link ExamSessionService} via the
 * shared {@code resolveOwnedSession}: DB-backed action permission + session
 * creator gated on ACTIVE organization membership (revoked creators denied)
 * or ACTIVE workspace OWNER/ADMIN. TeacherProfile/school ownership is no
 * longer an authorization authority. Personal-workspace sessions (no school)
 * get an explicit business denial for participant ADD and a clean,
 * policy-based denial for the other operations — never an NPE.
 *
 * <p>Query &amp; concurrency safety (A3.2-3B remediation): batched display
 * names (single JPQL projection), sort allowlist of entity properties only,
 * and session-then-participant pessimistic locks on block/unblock.
 */
@Service
public class ExamSessionParticipantService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORT_ALLOWLIST = Set.of("addedAt", "status");
    // Both uniqueness keys classify as duplicates: legacy (session, profile)
    // and V17 (session, user) — see P0-R9.
    private static final Set<String> DUPLICATE_CONSTRAINTS = Set.of(
            "uk_exam_session_participants_session_student",
            "uk_exam_session_participants_session_user");

    private final ExamAuthorizationService auth;
    private final ExamSessionService sessionService;
    private final ExamSessionRepository sessionRepository;
    private final ExamSessionParticipantRepository participantRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final EntityManager entityManager;

    public ExamSessionParticipantService(ExamAuthorizationService auth,
            ExamSessionService sessionService,
            ExamSessionRepository sessionRepository,
            ExamSessionParticipantRepository participantRepository,
            StudentProfileRepository studentProfileRepository,
            EntityManager entityManager) {
        this.auth = auth;
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.studentProfileRepository = studentProfileRepository;
        this.entityManager = entityManager;
    }

    // ============================================================
    // POST /api/exam-sessions/{sessionId}/participants — bulk add
    // ============================================================

    @SuppressWarnings("null")
    @Transactional
    public AddParticipantsResponse addParticipants(Long userId, Long sessionId, AddParticipantsRequest request) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_PARTICIPANT_ADD");
        ExamSession session = sessionService.resolveOwnedSession(userId, null, sessionId, true);
        // Add only in DRAFT or SCHEDULED.
        if (session.getStatus() != ExamSessionStatus.DRAFT && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }
        // Participant assignment is profile-scoped (school concept) in Phase 1;
        // personal-workspace sessions have no school — explicit denial, not NPE.
        if (session.getSchoolId() == null) {
            throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR,
                    "Participant assignment is only supported for organization (school) sessions in Phase 1");
        }

        // Deduplicate the request list (preserve first occurrence as the "valid"
        // candidate).
        List<Long> rawIds = request.studentProfileIds();
        Set<Long> seen = new LinkedHashSet<>();
        List<Long> duplicatedInRequest = new ArrayList<>();
        for (Long id : rawIds) {
            if (!seen.add(id)) {
                duplicatedInRequest.add(id);
            }
        }
        List<Long> distinctIds = new ArrayList<>(seen);

        // Batch-load student profiles + validate same school.
        Map<Long, StudentProfile> profiles = studentProfileRepository.findAllById(distinctIds).stream()
                .collect(Collectors.toMap(StudentProfile::getId, p -> p));

        // User-primary duplicate check (P0-R9): a user may already have a
        // V17 row WITHOUT profile linkage (blocked public learner) — classify
        // by userId so the legacy API returns a business duplicate instead of
        // hitting uk_exam_session_participants_session_user (500).
        List<Long> candidateUserIds = profiles.values().stream()
                .map(StudentProfile::getUserId).distinct().toList();
        Set<Long> existingUserIds = candidateUserIds.isEmpty() ? Set.of()
                : participantRepository.findAllByExamSessionIdAndUserIdIn(sessionId, candidateUserIds).stream()
                        .map(ExamSessionParticipant::getUserId)
                        .collect(Collectors.toSet());
        // Legacy profile linkage check (rows that still carry the profile id).
        Set<Long> existingProfileIds = participantRepository
                .findAllByExamSessionIdAndStudentProfileIdIn(sessionId, distinctIds).stream()
                .map(ExamSessionParticipant::getStudentProfileId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Long> toInsert = new ArrayList<>();
        List<Long> duplicated = new ArrayList<>(duplicatedInRequest);
        List<Long> invalid = new ArrayList<>();

        for (Long id : distinctIds) {
            StudentProfile sp = profiles.get(id);
            if (sp == null) {
                invalid.add(id); // missing
            } else if (!sp.getSchoolId().equals(session.getSchoolId())) {
                invalid.add(id); // cross-school
            } else if (existingUserIds.contains(sp.getUserId())
                    || existingProfileIds.contains(id)) {
                duplicated.add(id); // already a participant (user- or profile-linked)
            } else {
                toInsert.add(id); // valid
            }
        }

        // Insert valid participants (in same tx; concurrent race →
        // EXAM_PARTICIPANT_DUPLICATE). User-based rows (V17): the profile's
        // user is resolved eagerly so user_id is always present.
        if (!toInsert.isEmpty()) {
            try {
                for (Long spId : toInsert) {
                    StudentProfile sp = profiles.get(spId);
                    participantRepository.saveAndFlush(
                            new ExamSessionParticipant(sp.getUserId(), sessionId, spId,
                                    session.getSchoolId(), userId));
                }
            } catch (DataIntegrityViolationException ex) {
                if (isParticipantDuplicate(ex)) {
                    throw new ExamException(ExamErrorCode.EXAM_PARTICIPANT_DUPLICATE);
                }
                throw ex;
            }
        }

        Collections.sort(duplicated);
        Collections.sort(invalid);
        return new AddParticipantsResponse(toInsert.size(), duplicated, invalid);
    }

    // ============================================================
    // GET /api/exam-sessions/{sessionId}/participants — paginated list
    // ============================================================

    @SuppressWarnings("null")
    @Transactional
    public PageResponse<ExamSessionParticipantResponse> listParticipants(
            Long userId, Long sessionId, String statusStr, int page, int size, String sort) {
        auth.requireUserWithPermission(userId, "EXAM_SESSION_PARTICIPANT_READ");
        sessionService.resolveOwnedSession(userId, null, sessionId, false); // any state, no lock

        ExamSessionParticipantStatus statusFilter = parseStatusFilter(statusStr);
        PageRequest pageable = safePageable(page, size, sort);

        Page<ExamSessionParticipant> participants = participantRepository.findParticipants(sessionId, statusFilter,
                pageable);

        // Batch-load StudentProfiles for provenance (no N+1); profile-less
        // rows (V17) contribute no ids — never pass NULL into findAllById.
        List<Long> studentProfileIds = participants.getContent().stream()
                .map(ExamSessionParticipant::getStudentProfileId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, StudentProfile> profiles = studentProfileIds.isEmpty() ? Map.of()
                : studentProfileRepository.findAllById(studentProfileIds).stream()
                        .collect(Collectors.toMap(StudentProfile::getId, p -> p));

        // F1 (user-based, P0-R9): batch-load display names by participant
        // USER id in a SINGLE Hibernate-tracked query — profile-less rows
        // resolve too; legacy rows no longer depend on the profile join.
        Set<Long> userIds = participants.getContent().stream()
                .map(ExamSessionParticipant::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> displayNames = batchDisplayNames(userIds);

        List<ExamSessionParticipantResponse> items = participants.getContent().stream()
                .map(p -> {
                    StudentProfile sp = p.getStudentProfileId() != null
                            ? profiles.get(p.getStudentProfileId()) : null;
                    return new ExamSessionParticipantResponse(
                            p.getId(),
                            p.getUserId(),
                            p.getStudentProfileId(),
                            sp != null ? sp.getStudentCode() : null,
                            displayNames.get(p.getUserId()),
                            p.getStatus().name(),
                            p.getAddedAt(),
                            p.getBlockedAt());
                })
                .toList();

        return new PageResponse<>(items, participants.getNumber(), participants.getSize(),
                participants.getTotalElements(), participants.getTotalPages(),
                pageable.getSort().toString());
    }

    // ============================================================
    // POST .../block
    // ============================================================

    @Transactional
    public ExamSessionParticipantResponse blockParticipant(Long userId, Long sessionId, Long participantId) {
        return toggleBlock(userId, sessionId, participantId, true);
    }

    // ============================================================
    // POST .../unblock
    // ============================================================

    @Transactional
    public ExamSessionParticipantResponse unblockParticipant(Long userId, Long sessionId, Long participantId) {
        return toggleBlock(userId, sessionId, participantId, false);
    }

    // ============================================================
    // POST .../participants/by-user/{uid}/block | unblock (V17, P0-R8)
    // ============================================================

    /**
     * User-based block (issue #4 P0-R8): blocks a KNOWN participant of the
     * session by user id — including PUBLIC profile-less learners who already
     * started an attempt. If no participant row exists yet but the user has
     * an attempt in this session, a row is materialized (user-based, profile
     * provenance filled when known) and then blocked — one authoritative
     * source of BLOCKED state. Users who never participated and were never
     * assigned yield 404 (anti-enumeration).
     */
    @Transactional
    public ExamSessionParticipantResponse blockUser(Long userId, Long sessionId, Long targetUserId) {
        return toggleUserBlock(userId, sessionId, targetUserId, true);
    }

    /** User-based unblock — mirrors {@link #blockUser}. */
    @Transactional
    public ExamSessionParticipantResponse unblockUser(Long userId, Long sessionId, Long targetUserId) {
        return toggleUserBlock(userId, sessionId, targetUserId, false);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private ExamSessionParticipantResponse toggleBlock(Long userId, Long sessionId, Long participantId, boolean block) {
        auth.requireUserWithPermission(userId,
                block ? "EXAM_SESSION_PARTICIPANT_BLOCK" : "EXAM_SESSION_PARTICIPANT_UNBLOCK");
        // Lock order: session FIRST, then participant. Concurrent toggles serialize
        // here and re-read the latest committed state, so @Version never conflicts
        // on flush. Shared workspace policy (issue #4 P0-R6).
        ExamSession session = sessionService.resolveOwnedSession(userId, null, sessionId, true);
        // Block/unblock allowed in DRAFT, SCHEDULED, OPEN.
        if (session.getStatus() != ExamSessionStatus.DRAFT
                && session.getStatus() != ExamSessionStatus.SCHEDULED
                && session.getStatus() != ExamSessionStatus.OPEN) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }

        ExamSessionParticipant p = participantRepository.findByIdAndExamSessionIdForUpdate(participantId, sessionId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_PARTICIPANT_NOT_FOUND));

        applyToggle(p, block);
        return toResponse(p);
    }

    /**
     * User-based toggle (V17, P0-R8). Materializes a participant row for a
     * known participant (existing row, else a user with an attempt in the
     * session) under the session + participant locks; races resolve via the
     * (session, user) unique index.
     */
    private ExamSessionParticipantResponse toggleUserBlock(Long userId, Long sessionId, Long targetUserId,
            boolean block) {
        auth.requireUserWithPermission(userId,
                block ? "EXAM_SESSION_PARTICIPANT_BLOCK" : "EXAM_SESSION_PARTICIPANT_UNBLOCK");
        ExamSession session = sessionService.resolveOwnedSession(userId, null, sessionId, true);
        if (session.getStatus() != ExamSessionStatus.DRAFT
                && session.getStatus() != ExamSessionStatus.SCHEDULED
                && session.getStatus() != ExamSessionStatus.OPEN) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }

        ExamSessionParticipant p = participantRepository
                .findByExamSessionIdAndUserIdForUpdate(sessionId, targetUserId)
                .orElse(null);
        if (p == null) {
            // Known participant = user with an attempt in this session
            // (roster P0-R5 semantics). Strangers → 404 anti-enumeration.
            Long attemptCount = entityManager.createQuery(
                            "SELECT COUNT(a) FROM Attempt a WHERE a.examSessionId = :sid AND a.userId = :uid",
                            Long.class)
                    .setParameter("sid", sessionId)
                    .setParameter("uid", targetUserId)
                    .getSingleResult();
            if (attemptCount == null || attemptCount == 0) {
                throw new ExamException(ExamErrorCode.EXAM_PARTICIPANT_NOT_FOUND);
            }
            // Fill legacy provenance when the user has a profile (nullable).
            Object[] provenance = (Object[]) entityManager.createNativeQuery(
                            "SELECT sp.id, sp.school_id FROM student_profiles sp WHERE sp.user_id = :uid LIMIT 1")
                    .setParameter("uid", targetUserId)
                    .getResultList().stream().findFirst().orElse(null);
            Long profileId = provenance != null ? ((Number) provenance[0]).longValue() : null;
            Long schoolId = provenance != null ? ((Number) provenance[1]).longValue() : null;
            p = new ExamSessionParticipant(targetUserId, sessionId, profileId, schoolId, userId);
            try {
                p = participantRepository.saveAndFlush(p);
                // Re-acquire the row lock for the toggle path parity.
                p = participantRepository.findByExamSessionIdAndUserIdForUpdate(sessionId, targetUserId)
                        .orElse(p);
            } catch (DataIntegrityViolationException race) {
                // Concurrent block materialized the row; adopt it.
                p = participantRepository.findByExamSessionIdAndUserIdForUpdate(sessionId, targetUserId)
                        .orElseThrow(() -> race);
            }
        }

        applyToggle(p, block);
        return toResponse(p);
    }

    private void applyToggle(ExamSessionParticipant p, boolean block) {
        if (block) {
            if (p.getStatus() != ExamSessionParticipantStatus.BLOCKED) {
                p.block(Instant.now());
                participantRepository.saveAndFlush(p);
            } // else idempotent
        } else {
            if (p.getStatus() != ExamSessionParticipantStatus.ELIGIBLE) {
                p.unblock();
                participantRepository.saveAndFlush(p);
            } // else idempotent
        }
    }

    private ExamSessionParticipantResponse toResponse(ExamSessionParticipant p) {
        StudentProfile sp = p.getStudentProfileId() != null
                ? studentProfileRepository.findById(p.getStudentProfileId()).orElse(null)
                : null;
        String displayName = p.getUserId() != null
                ? batchDisplayNames(Set.of(p.getUserId())).get(p.getUserId()) : null;
        return new ExamSessionParticipantResponse(
                p.getId(), p.getUserId(), p.getStudentProfileId(),
                sp != null ? sp.getStudentCode() : null, displayName,
                p.getStatus().name(), p.getAddedAt(), p.getBlockedAt());
    }

    /**
     * F1: fetch display names for a set of users in a SINGLE Hibernate-tracked JPQL
     * projection (no per-user loop, no N+1). Tracked so query statistics can prove
     * it runs once per request.
     */
    private Map<Long, String> batchDisplayNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createQuery(
                "SELECT u.id, u.displayName FROM User u WHERE u.id IN :ids", Object[].class)
                .setParameter("ids", userIds)
                .getResultList();
        Map<Long, String> names = new HashMap<>();
        for (Object[] row : rows) {
            names.put((Long) row[0], (String) row[1]);
        }
        return names;
    }

    private ExamSessionParticipantStatus parseStatusFilter(String statusStr) {
        if (statusStr == null || statusStr.isBlank())
            return null;
        try {
            return ExamSessionParticipantStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
        }
    }

    private PageRequest safePageable(int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Sort safeSort = Sort.by(Sort.Direction.DESC, "addedAt");
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String prop = parts[0].trim();
            Sort.Direction dir = (parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc"))
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;
            if (SORT_ALLOWLIST.contains(prop)) {
                safeSort = Sort.by(dir, prop);
            } else {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
        }
        return PageRequest.of(safePage, safeSize, safeSort);
    }

    private static boolean isParticipantDuplicate(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && DUPLICATE_CONSTRAINTS.contains(name))
                    return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
