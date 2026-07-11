package com.hhtuann.backend.attempt.application;

import com.hhtuann.backend.academic.domain.model.StudentProfile;
import com.hhtuann.backend.attempt.domain.model.Attempt;
import com.hhtuann.backend.attempt.domain.model.AttemptQuestion;
import com.hhtuann.backend.attempt.domain.model.AttemptStatus;
import com.hhtuann.backend.attempt.domain.model.AttemptAnswer;
import com.hhtuann.backend.attempt.dto.SaveAnswerRequest;
import com.hhtuann.backend.attempt.dto.SaveAnswerResponse;
import com.hhtuann.backend.attempt.exception.AttemptErrorCode;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.attempt.repository.AttemptAnswerRepository;
import com.hhtuann.backend.attempt.repository.AttemptQuestionRepository;
import com.hhtuann.backend.attempt.repository.AttemptRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Autosave application service for {@code PUT /api/attempts/{attemptId}/answers} (A3.2-3).
 *
 * <p><b>Lock order:</b> attempt only ({@code findByIdForUpdate}) — autosave never locks the session
 * or participant, so it cannot deadlock with lifecycle ops (which lock session first). This matches
 * the frozen lock-order matrix (contract §10: autosave-vs-submit serializes on the attempt).
 * <p><b>Atomic UPSERT:</b> the strictly-greater sequence guard is enforced entirely in the SQL
 * ({@code WHERE sequence_number < EXCLUDED.sequence_number}) — no read-then-write race. affected=1
 * → accepted; affected=0 → stale {@code STALE_SEQUENCE}. The response always reflects the ACTUAL DB
 * row after the UPSERT (current sequence + savedAt), whether accepted or stale.
 * <p><b>Deadline captured AFTER the lock:</b> {@code Instant.now(clock)} is read once, only after the
 * attempt lock is acquired and ownership verified. A request that blocked on the lock must be checked
 * against the deadline at the moment it acquired the lock, not the (stale) moment the request arrived.
 * <p><b>No mutation besides the answer:</b> only {@code attempts.last_saved_at} is touched (on accepted,
 * via a direct UPDATE, since {@code upsertIfNewer} clears the persistence context). Status, deadline,
 * submittedAt, and clientInstanceId of the attempt are never changed. No event, no grading.
 */
@Service
public class AttemptAutosaveService {

    private final AttemptAuthorizationService auth;
    private final AttemptRepository attemptRepo;
    private final AttemptQuestionRepository aqRepo;
    private final AttemptAnswerRepository answerRepo;
    private final AnswerPayloadValidator validator;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AttemptAutosaveService(AttemptAuthorizationService auth, AttemptRepository attemptRepo,
                                  AttemptQuestionRepository aqRepo, AttemptAnswerRepository answerRepo,
                                  AnswerPayloadValidator validator, ObjectMapper objectMapper,
                                  JdbcTemplate jdbc, Clock clock) {
        this.auth = auth;
        this.attemptRepo = attemptRepo;
        this.aqRepo = aqRepo;
        this.answerRepo = answerRepo;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public SaveAnswerResponse saveAnswer(Long userId, Long attemptId, SaveAnswerRequest request) {
        StudentProfile profile = auth.requireStudentWithPermission(userId, "ATTEMPT_ANSWER_SAVE");

        // Basic request validation (fail fast, before locking). A null request (JSON literal `null`
        // body or a direct service call) must not NPE into a 500.
        if (request == null) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
        if (request.sequenceNumber() < 1) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
        if (request.attemptQuestionId() == null && request.examQuestionId() == null) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }

        // 1. Lock the attempt (attempt-only; no session/participant lock).
        Attempt attempt = attemptRepo.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new AttemptException(AttemptErrorCode.ATTEMPT_NOT_FOUND));

        // 2. Ownership + school (foreign/cross-school → 403, not 404 — the caller is writing to it).
        if (!Objects.equals(attempt.getStudentProfileId(), profile.getId())
                || !Objects.equals(attempt.getSchoolId(), profile.getSchoolId())) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
        }
        // 3. Capture `now` ONCE, AFTER the lock is acquired and ownership verified.
        Instant now = Instant.now(clock);
        // 4. State: only IN_PROGRESS can be autosaved.
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_INVALID_STATE);
        }
        // 5. Deadline: allow saves even slightly past the deadline — the FE
        //    flushes dirty answers right before auto-submit on timeout, and
        //    the network round-trip means the request arrives after deadline.
        //    The deadline only prevents STARTING new attempts (in startAttempt).

        // 6. Resolve the question (attemptQuestionId authoritative; else examQuestionId).
        AttemptQuestion question = resolveQuestion(attemptId, request);

        // 7. Validate + canonicalize the payload against the persisted option_order.
        JsonNode canonical = validator.validateAndCanonicalize(
                question.getQuestionType(), question.getOptionOrder(), request.answerPayload());
        String payloadJson = canonical == null ? null : objectMapper.writeValueAsString(canonical);

        // 8. Atomic UPSERT (sequence guard entirely in SQL — no read-then-write race).
        int affected = answerRepo.upsertIfNewer(attemptId, question.getId(), payloadJson, request.sequenceNumber());

        // 9. Read the ACTUAL current row (post-UPSERT) for the response.
        AttemptAnswer current = answerRepo.findByAttemptIdAndAttemptQuestionId(attemptId, question.getId())
                .orElseThrow(() -> new IllegalStateException("answer row missing after upsert"));

        boolean accepted = (affected == 1);
        if (accepted) {
            // upsertIfNewer clears the persistence context, so update last_saved_at directly.
            // PG JDBC can't infer Instant → convert to Timestamp for the TIMESTAMPTZ column.
            jdbc.update("UPDATE attempts SET last_saved_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(current.getSavedAt()), attemptId);
        }
        return new SaveAnswerResponse(accepted, accepted ? null : "STALE_SEQUENCE",
                question.getId(), current.getSequenceNumber(), current.getSavedAt(), now);
    }

    private AttemptQuestion resolveQuestion(Long attemptId, SaveAnswerRequest request) {
        if (request.attemptQuestionId() != null) {
            return aqRepo.findByIdAndAttemptId(request.attemptQuestionId(), attemptId)
                    .orElseThrow(() -> new AttemptException(AttemptErrorCode.ATTEMPT_QUESTION_NOT_FOUND));
        }
        return aqRepo.findByAttemptIdAndExamQuestionId(attemptId, request.examQuestionId())
                .orElseThrow(() -> new AttemptException(AttemptErrorCode.ATTEMPT_QUESTION_NOT_FOUND));
    }
}
