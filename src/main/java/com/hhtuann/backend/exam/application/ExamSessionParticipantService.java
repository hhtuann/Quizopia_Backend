package com.hhtuann.backend.exam.application;

import com.hhtuann.backend.academic.domain.model.StudentProfile;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.StudentProfileRepository;
import com.hhtuann.backend.exam.domain.model.*;
import com.hhtuann.backend.exam.dto.*;
import com.hhtuann.backend.exam.exception.ExamErrorCode;
import com.hhtuann.backend.exam.exception.ExamException;
import com.hhtuann.backend.exam.repository.*;
import com.hhtuann.backend.question.dto.PageResponse;
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
 * Application service for the Exam Session Participant API — 4 endpoints
 * (A3.2-3B):
 * <ul>
 * <li>POST /api/exam-sessions/{sessionId}/participants — bulk add (partial
 * classification)</li>
 * <li>GET /api/exam-sessions/{sessionId}/participants — paginated list</li>
 * <li>POST .../participants/{participantId}/block — block participant</li>
 * <li>POST .../participants/{participantId}/unblock — unblock participant</li>
 * </ul>
 *
 * <p>
 * Query &amp; concurrency safety (A3.2-3B remediation):
 * <ul>
 * <li>F1 — display names fetched in a single Hibernate-tracked JPQL batch
 * ({@code SELECT u.id, u.displayName FROM User u WHERE u.id IN :ids}); no
 * per-user loop.</li>
 * <li>F2 — {@code studentCode} is NOT a property of the participant entity, so
 * it is removed
 * from the sort allowlist; a {@code studentCode} sort request throws
 * EXAM_VALIDATION_ERROR (400).</li>
 * <li>F4/F5 — every mutating operation resolves the session with
 * {@code findByIdForUpdate}
 * and block/unblock additionally lock the participant row, so concurrent
 * toggles serialize
 * and re-read the latest committed state (no {@code @Version}
 * OptimisticLockException).</li>
 * </ul>
 */
@Service
public class ExamSessionParticipantService {

    private static final int MAX_PAGE_SIZE = 100;
    // studentCode is a StudentProfile field, not a participant-entity field;
    // sorting by it
    // can't be resolved by the ExamSessionParticipant JPQL and would raise a 500.
    // Allowlist only
    // entity properties, so an unsupported sort returns EXAM_VALIDATION_ERROR (400)
    // instead.
    private static final Set<String> SORT_ALLOWLIST = Set.of("addedAt", "status");
    private static final String DUPLICATE_CONSTRAINT = "uk_exam_session_participants_session_student";

    private final ExamAuthorizationService auth;
    private final ExamSessionRepository sessionRepository;
    private final ExamSessionParticipantRepository participantRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final EntityManager entityManager;

    public ExamSessionParticipantService(ExamAuthorizationService auth,
            ExamSessionRepository sessionRepository,
            ExamSessionParticipantRepository participantRepository,
            StudentProfileRepository studentProfileRepository,
            EntityManager entityManager) {
        this.auth = auth;
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
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_PARTICIPANT_ADD");
        ExamSession session = resolveOwnedSessionForUpdate(profile, sessionId);
        // Add only in DRAFT or SCHEDULED.
        if (session.getStatus() != ExamSessionStatus.DRAFT && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
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

        // Batch-check existing participants in DB.
        Set<Long> existing = participantRepository
                .findAllByExamSessionIdAndStudentProfileIdIn(sessionId, distinctIds).stream()
                .map(ExamSessionParticipant::getStudentProfileId)
                .collect(Collectors.toSet());

        // Batch-load student profiles + validate same school.
        Map<Long, StudentProfile> profiles = studentProfileRepository.findAllById(distinctIds).stream()
                .collect(Collectors.toMap(StudentProfile::getId, p -> p));

        List<Long> toInsert = new ArrayList<>();
        List<Long> duplicated = new ArrayList<>(duplicatedInRequest);
        List<Long> invalid = new ArrayList<>();

        for (Long id : distinctIds) {
            StudentProfile sp = profiles.get(id);
            if (sp == null) {
                invalid.add(id); // missing
            } else if (!sp.getSchoolId().equals(session.getSchoolId())) {
                invalid.add(id); // cross-school
            } else if (existing.contains(id)) {
                duplicated.add(id); // already a participant
            } else {
                toInsert.add(id); // valid
            }
        }

        // Insert valid participants (in same tx; concurrent race →
        // EXAM_PARTICIPANT_DUPLICATE).
        if (!toInsert.isEmpty()) {
            try {
                for (Long spId : toInsert) {
                    participantRepository.saveAndFlush(
                            new ExamSessionParticipant(session.getSchoolId(), sessionId, spId, userId));
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
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_SESSION_PARTICIPANT_READ");
        resolveOwnedSession(profile, sessionId); // read-only: any session state OK, no pessimistic lock

        ExamSessionParticipantStatus statusFilter = parseStatusFilter(statusStr);
        PageRequest pageable = safePageable(page, size, sort);

        Page<ExamSessionParticipant> participants = participantRepository.findParticipants(sessionId, statusFilter,
                pageable);

        // Batch-load StudentProfiles (no N+1).
        List<Long> studentProfileIds = participants.getContent().stream()
                .map(ExamSessionParticipant::getStudentProfileId).toList();
        Map<Long, StudentProfile> profiles = studentProfileIds.isEmpty() ? Map.of()
                : studentProfileRepository.findAllById(studentProfileIds).stream()
                        .collect(Collectors.toMap(StudentProfile::getId, p -> p));

        // F1: batch-load display names in a SINGLE Hibernate-tracked query (no per-user
        // loop).
        Set<Long> userIds = profiles.values().stream().map(StudentProfile::getUserId).collect(Collectors.toSet());
        Map<Long, String> displayNames = batchDisplayNames(userIds);

        List<ExamSessionParticipantResponse> items = participants.getContent().stream()
                .map(p -> {
                    StudentProfile sp = profiles.get(p.getStudentProfileId());
                    return new ExamSessionParticipantResponse(
                            p.getId(),
                            p.getStudentProfileId(),
                            sp != null ? sp.getStudentCode() : null,
                            sp != null ? displayNames.get(sp.getUserId()) : null,
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
    // Helpers
    // ============================================================

    private ExamSessionParticipantResponse toggleBlock(Long userId, Long sessionId, Long participantId, boolean block) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId,
                block ? "EXAM_SESSION_PARTICIPANT_BLOCK" : "EXAM_SESSION_PARTICIPANT_UNBLOCK");
        // Lock order: session FIRST, then participant. Concurrent toggles serialize
        // here and
        // re-read the latest committed state, so @Version never conflicts on flush.
        ExamSession session = resolveOwnedSessionForUpdate(profile, sessionId);
        // Block/unblock allowed in DRAFT, SCHEDULED, OPEN.
        if (session.getStatus() != ExamSessionStatus.DRAFT
                && session.getStatus() != ExamSessionStatus.SCHEDULED
                && session.getStatus() != ExamSessionStatus.OPEN) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_INVALID_STATE);
        }

        ExamSessionParticipant p = participantRepository.findByIdAndExamSessionIdForUpdate(participantId, sessionId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_PARTICIPANT_NOT_FOUND));

        if (block) {
            if (p.getStatus() == ExamSessionParticipantStatus.BLOCKED) {
                // Idempotent: already blocked.
            } else {
                p.block(Instant.now());
                participantRepository.saveAndFlush(p);
            }
        } else {
            if (p.getStatus() == ExamSessionParticipantStatus.ELIGIBLE) {
                // Idempotent: already eligible.
            } else {
                p.unblock();
                participantRepository.saveAndFlush(p);
            }
        }

        StudentProfile sp = studentProfileRepository.findById(p.getStudentProfileId()).orElse(null);
        String displayName = sp != null ? batchDisplayNames(Set.of(sp.getUserId())).get(sp.getUserId()) : null;
        return new ExamSessionParticipantResponse(
                p.getId(), p.getStudentProfileId(),
                sp != null ? sp.getStudentCode() : null, displayName,
                p.getStatus().name(), p.getAddedAt(), p.getBlockedAt());
    }

    /**
     * Read-only resolution (list): no pessimistic lock, any session state accepted.
     */
    private ExamSession resolveOwnedSession(TeacherProfile profile, Long sessionId) {
        ExamSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_SESSION_NOT_FOUND));
        if (!session.getOwnerTeacherId().equals(profile.getId())
                || !session.getSchoolId().equals(profile.getSchoolId())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED);
        }
        return session;
    }

    /**
     * Mutating resolution (add/block/unblock): pessimistic-write lock the session
     * row so the
     * state check is stable for the whole operation — a concurrent lifecycle
     * transition that
     * changes the state is observed after this tx acquires the lock (re-read), not
     * a stale read.
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

    /**
     * F1: fetch display names for a set of users in a SINGLE Hibernate-tracked JPQL
     * projection
     * (no per-user loop, no N+1). Tracked so query statistics can prove it runs
     * once per request.
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
                if (name != null && name.contains(DUPLICATE_CONSTRAINT))
                    return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
