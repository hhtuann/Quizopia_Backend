package com.quizopia.backend.attempt.application;

import com.quizopia.backend.academic.domain.model.StudentProfile;
import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.domain.model.AttemptStatus;
import com.quizopia.backend.attempt.domain.model.IdempotencyOperation;
import com.quizopia.backend.attempt.domain.model.IdempotencyRecord;
import com.quizopia.backend.attempt.dto.SubmitRequest;
import com.quizopia.backend.attempt.dto.SubmitResponse;
import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.attempt.repository.IdempotencyRecordRepository;
import com.quizopia.backend.realtime.event.AttemptRealtimeEvent;
import com.quizopia.backend.realtime.event.RealtimeEventType;
import jakarta.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Idempotent submit application service for {@code POST /api/attempts/{attemptId}/submit} (A3.2-4).
 *
 * <p><b>Lock order:</b> attempt only ({@code findByIdForUpdate}) — submit never locks the session or
 * participant, so it cannot deadlock with lifecycle ops (contract §10: submit-vs-autosave and
 * submit-vs-submit serialize on the attempt).
 *
 * <p><b>Status precedence:</b> GRADED → 409 {@code ATTEMPT_INVALID_STATE}. SUBMITTED + same key →
 * {@code IMMUTABLE_CACHED_RESPONSE} (deserialize this attempt's cached JSON; never recompute, never
 * mutate). SUBMITTED + different key → 409 {@code ATTEMPT_ALREADY_SUBMITTED}. IN_PROGRESS → first
 * submit: precheck cross-attempt key reuse, capture {@code now} AFTER the lock, deadline check,
 * transition {@code IN_PROGRESS→SUBMITTED}, insert the idempotency cache, flush.
 *
 * <p><b>Atomic rollback:</b> the transition + cache insert share one transaction; the constraint
 * translator maps the three idempotency UQs to 409 {@code ATTEMPT_IDEMPOTENCY_CONFLICT} and rethrows
 * anything unknown (generic 500). A cache insert failure rolls the attempt back to IN_PROGRESS.
 *
 * <p><b>No event:</b> Day 7 submit publishes no WebSocket/event (realtime is a later checkpoint).
 */
@Service
public class AttemptSubmitService {

    private static final Logger log = LoggerFactory.getLogger(AttemptSubmitService.class);

    /** Submit-path idempotency constraints → 409 ATTEMPT_IDEMPOTENCY_CONFLICT. */
    private static final Set<String> IDEMPOTENCY_CONFLICT_CONSTRAINTS = Set.of(
            "uk_idempotency_user_operation_key",   // cross-attempt key reuse (user, operation, key)
            "uk_idempotency_attempt_operation",    // a second cache for the same attempt (D26)
            "uk_attempts_student_submit_key");     // partial UQ (student_profile, submission_key) on attempts

    private final AttemptAuthorizationService auth;
    private final AttemptRepository attemptRepo;
    private final IdempotencyRecordRepository idempotencyRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final com.quizopia.backend.grading.AttemptGradingService gradingService;
    private final com.quizopia.backend.notification.application.NotificationService notificationService;
    private final com.quizopia.backend.academic.repository.StudentProfileRepository studentProfileRepo;

    public AttemptSubmitService(AttemptAuthorizationService auth, AttemptRepository attemptRepo,
                                IdempotencyRecordRepository idempotencyRepo, ObjectMapper objectMapper,
                                Clock clock, ApplicationEventPublisher eventPublisher,
                                com.quizopia.backend.grading.AttemptGradingService gradingService,
                                com.quizopia.backend.notification.application.NotificationService notificationService,
                                com.quizopia.backend.academic.repository.StudentProfileRepository studentProfileRepo) {
        this.auth = auth;
        this.attemptRepo = attemptRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.gradingService = gradingService;
        this.notificationService = notificationService;
        this.studentProfileRepo = studentProfileRepo;
    }

    @Transactional
    public SubmitResponse submitAttempt(Long userId, Long attemptId, SubmitRequest request) {
        StudentProfile profile = auth.requireStudentWithPermission(userId, "ATTEMPT_SUBMIT");

        // Basic request validation (fail fast, before locking). A null request (JSON literal `null`
        // body or a direct service call) must not NPE into a 500.
        if (request == null) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
        String key = request.submissionIdempotencyKey();
        if (!isValidKey(key)) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }

        // 1. Lock the attempt (attempt-only; no session/participant lock).
        Attempt attempt = attemptRepo.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new AttemptException(AttemptErrorCode.ATTEMPT_NOT_FOUND));

        // 2. Ownership + school (foreign/cross-school → 403 — this is a write).
        if (!Objects.equals(attempt.getStudentProfileId(), profile.getId())
                || !Objects.equals(attempt.getSchoolId(), profile.getSchoolId())) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
        }

        // 3. Status precedence.
        if (attempt.getStatus() == AttemptStatus.GRADED) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_INVALID_STATE);
        }
        if (attempt.getStatus() == AttemptStatus.SUBMITTED) {
            if (key.equals(attempt.getSubmissionIdempotencyKey())) {
                // IMMUTABLE_CACHED_RESPONSE: read this attempt's cached JSON verbatim.
                return loadImmutableCachedResponse(userId, attempt, key);
            }
            throw new AttemptException(AttemptErrorCode.ATTEMPT_ALREADY_SUBMITTED);
        }

        // IN_PROGRESS — precheck cross-attempt key reuse (record for another attempt).
        IdempotencyRecord existing = idempotencyRepo
                .findByUserIdAndOperationAndIdempotencyKey(userId, IdempotencyOperation.ATTEMPT_SUBMIT, key)
                .orElse(null);
        if (existing != null) {
            // UQ (user, operation, key) guarantees the record is for a different attempt (this attempt
            // is IN_PROGRESS, so no cache for it can exist). A same-attempt record here would be an
            // inconsistent state → generic 500.
            throw new AttemptException(AttemptErrorCode.ATTEMPT_IDEMPOTENCY_CONFLICT);
        }

        // 4. Capture `now` ONCE, AFTER the lock. Strict deadline enforcement:
        //    manual submit after deadline → 409 ATTEMPT_DEADLINE_EXCEEDED.
        //    The sweeper (finalizeAttempt) bypasses this check via an internal-only path
        //    and uses attempt.getDeadlineAt() as submittedAt — it does NOT call submitAttempt.
        Instant now = Instant.now(clock);
        if (now.isAfter(attempt.getDeadlineAt())) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_DEADLINE_EXCEEDED);
        }

        // 5. Transition + grade (atomic) + freeze response + cache. Grading runs inside this transaction
        //    (Propagation.REQUIRED); a GradingException rolls the whole submit — status, Grade, GradeItems,
        //    and the idempotency cache — back to IN_PROGRESS. No partial grade, no event on rollback.
        attempt.submit(now, key);
        com.quizopia.backend.attempt.domain.model.Grade grade = gradingService.gradeAndPersist(attempt);
        SubmitResponse response = new SubmitResponse(
                attempt.getId(), AttemptStatus.SUBMITTED.name(), now, now, attempt.getAttemptNumber(),
                attempt.getExamSessionId(), grade.getFinalScore(), grade.getMaxScore(), grade.getPercentage());
        try {
            JsonNode body = objectMapper.valueToTree(response);
            IdempotencyRecord record = new IdempotencyRecord(
                    userId, attemptId, IdempotencyOperation.ATTEMPT_SUBMIT, key, 200, body);
            idempotencyRepo.save(record);
            idempotencyRepo.flush(); // flush transition + cache → surface UQ violations
        } catch (DataIntegrityViolationException | PersistenceException e) {
            String constraint = AttemptService.extractConstraintName(e);
            if (IDEMPOTENCY_CONFLICT_CONSTRAINTS.contains(constraint)) {
                // Race loser: another tx won the (user, operation, key) / (attempt, operation) /
                // (student, submission_key) UQ. The whole tx (incl. the attempt transition) rolls back.
                throw new AttemptException(AttemptErrorCode.ATTEMPT_IDEMPOTENCY_CONFLICT);
            }
            log.warn("Untranslated data-integrity violation on submit (constraint={}); rethrowing original",
                    constraint != null ? constraint : "<none>");
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
        // Publish ATTEMPT_SUBMITTED within the tx — delivered AFTER_COMMIT only. Cached retry,
        // different-key, conflict, deadline, GRADED and rollback paths never reach here, so no
        // duplicate/spurious event and the immutable cached response is untouched.
        eventPublisher.publishEvent(new AttemptRealtimeEvent(
                RealtimeEventType.ATTEMPT_SUBMITTED, attempt.getExamSessionId(), attempt.getId(),
                attempt.getStudentProfileId(), now));
        // Notify the student that their result is graded.
        notificationService.create(userId,
                com.quizopia.backend.notification.domain.model.NotificationType.RESULT_GRADED,
                "Result available",
                "Your exam has been graded: " + grade.getFinalScore() + "/" + grade.getMaxScore(),
                "/attempts/" + attemptId + "/result");
        return response;
    }

    // ============================================================
    // Timeout auto-finalize (server-side sweep)
    // ============================================================

    /**
     * IDs of IN_PROGRESS attempts whose deadline has passed — the work-list for
     * {@link com.quizopia.backend.attempt.application.AttemptTimeoutScheduler}.
     * Read-only; the sweeper finalizes each ID via {@link #finalizeAttempt(Long)}
     * (own transaction, own lock).
     */
    @Transactional(readOnly = true)
    public List<Long> findExpiredAttemptIds() {
        return attemptRepo.findExpiredInProgressIds(AttemptStatus.IN_PROGRESS, Instant.now(clock));
    }

    /**
     * Finalize one expired IN_PROGRESS attempt: transition to SUBMITTED with
     * {@code submittedAt = deadlineAt} (NOT now — the attempt is recorded as
     * submitted when the allotted time expired, regardless of when this sweep
     * runs), then grade and notify. Server-side backstop for students who left
     * the attempt page before the timer hit 0 (the client-side auto-submit only
     * fires while the page is open).
     *
     * <p>Own transaction + pessimistic lock per attempt so one failure can't roll
     * back others and locks are short-lived. The status re-check after the lock
     * handles the race with a concurrent manual/client submit (already SUBMITTED
     * → no-op). No idempotency-cache row is inserted: the auto-timeout key is
     * never known to any client, so no cached retry can target it.
     */
    @Transactional
    public void finalizeAttempt(Long attemptId) {
        Attempt attempt = attemptRepo.findByIdForUpdate(attemptId).orElse(null);
        if (attempt == null) {
            return;
        }
        // Race: a manual/client submit beat the sweep to it — nothing to do.
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            return;
        }
        Instant deadline = attempt.getDeadlineAt();
        // submittedAt = the deadline (not now): duration is recorded as exactly
        // the allowed time, not start→now.
        String key = "auto-timeout-" + attemptId;
        attempt.submit(deadline, key);
        com.quizopia.backend.attempt.domain.model.Grade grade = gradingService.gradeAndPersist(attempt);
        attemptRepo.saveAndFlush(attempt); // surface uk_attempts_student_submit_key etc.

        // Realtime + notification mirror the manual submit path.
        eventPublisher.publishEvent(new AttemptRealtimeEvent(
                RealtimeEventType.ATTEMPT_SUBMITTED, attempt.getExamSessionId(), attempt.getId(),
                attempt.getStudentProfileId(), deadline));
        StudentProfile profile = studentProfileRepo.findById(attempt.getStudentProfileId()).orElse(null);
        if (profile != null) {
            notificationService.create(profile.getUserId(),
                    com.quizopia.backend.notification.domain.model.NotificationType.RESULT_GRADED,
                    "Result available",
                    "Time ran out — your exam was auto-submitted and graded: "
                            + grade.getFinalScore() + "/" + grade.getMaxScore(),
                    "/attempts/" + attemptId + "/result");
        }
    }

    /**
     * Loads the cached submit response for a same-key retry. Reads {@code response_body} verbatim and
     * validates it is a consistent SubmitResponse for this exact attempt. Any inconsistency (missing
     * record, ownership mismatch, malformed/inconsistent body) → generic {@link IllegalStateException}
     * (sanitized 500 via {@code GlobalExceptionHandler}); the cache is never rebuilt, never returned
     * partially.
     */
    private SubmitResponse loadImmutableCachedResponse(Long userId, Attempt attempt, String key) {
        IdempotencyRecord record = idempotencyRepo
                .findByAttemptIdAndOperation(attempt.getId(), IdempotencyOperation.ATTEMPT_SUBMIT)
                .orElseThrow(() -> cacheFailure("cached submit record missing"));
        if (!Objects.equals(record.getUserId(), userId)
                || !Objects.equals(record.getAttemptId(), attempt.getId())
                || !Objects.equals(record.getIdempotencyKey(), key)) {
            throw cacheFailure("cached submit record ownership mismatch");
        }
        SubmitResponse cached;
        try {
            cached = objectMapper.convertValue(record.getResponseBody(), SubmitResponse.class);
        } catch (RuntimeException e) {
            throw cacheFailure("cached submit body malformed");
        }
        if (cached == null
                || !Objects.equals(cached.attemptId(), attempt.getId())
                || !"SUBMITTED".equals(cached.status())
                || cached.submittedAt() == null
                || cached.serverTime() == null
                || !Objects.equals(cached.attemptNumber(), attempt.getAttemptNumber())) {
            throw cacheFailure("cached submit response inconsistent");
        }
        return cached;
    }

    private static IllegalStateException cacheFailure(String reason) {
        // Generic reason (no raw body / SQL / constraint / class name) → GlobalExceptionHandler
        // returns a sanitized INTERNAL_ERROR 500.
        return new IllegalStateException(reason);
    }

    /**
     * A valid idempotency key is a non-null, non-empty string of at most 100 characters with NO
     * whitespace character at any position. No trim, no normalization — the exact client string is
     * used as-is for storage and comparison.
     */
    private static boolean isValidKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 100) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isWhitespace(key.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
