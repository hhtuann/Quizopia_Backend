package com.quizopia.backend.exam.application;

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
 * Application service for the Exam Session Participant API:
 * <ul>
 * <li>GET /api/exam-sessions/{sessionId}/participants — paginated list</li>
 * <li>POST .../participants/{participantId}/block — block participant</li>
 * <li>POST .../participants/{participantId}/unblock — unblock participant</li>
 * <li>POST .../participants/by-user/{uid}/block | unblock — user-based
 *     moderation incl. PUBLIC learners who only hold attempts</li>
 * </ul>
 *
 * <p>Authorization: all endpoints enforce session ownership via the shared
 * {@code resolveOwnedSession} (creator-only). The legacy profile-based bulk
 * add was decommissioned with the school/organization architecture; session
 * access is governed by visibility (PUBLIC / CLASS_RESTRICTED) and BLOCKED
 * rows only.
 *
 * <p>Query &amp; concurrency safety: batched display names (single JPQL
 * projection), sort allowlist of entity properties only, and
 * session-then-participant pessimistic locks on block/unblock.
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
    private final EntityManager entityManager;

    public ExamSessionParticipantService(ExamAuthorizationService auth,
            ExamSessionService sessionService,
            ExamSessionRepository sessionRepository,
            ExamSessionParticipantRepository participantRepository,
            EntityManager entityManager) {
        this.auth = auth;
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.entityManager = entityManager;
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

        // Batch display names by participant USER id in a SINGLE
        // Hibernate-tracked query (F1, no N+1).
        Set<Long> userIds = participants.getContent().stream()
                .map(ExamSessionParticipant::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> displayNames = batchDisplayNames(userIds);

        List<ExamSessionParticipantResponse> items = participants.getContent().stream()
                .map(p -> new ExamSessionParticipantResponse(
                        p.getId(),
                        p.getUserId(),
                        null,
                        null,
                        displayNames.get(p.getUserId()),
                        p.getStatus().name(),
                        p.getAddedAt(),
                        p.getBlockedAt()))
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
            p = new ExamSessionParticipant(targetUserId, sessionId, userId);
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
        String displayName = p.getUserId() != null
                ? batchDisplayNames(Set.of(p.getUserId())).get(p.getUserId()) : null;
        return new ExamSessionParticipantResponse(
                p.getId(), p.getUserId(), null, null, displayName,
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
