package com.quizopia.backend.attempt.api;

import com.quizopia.backend.attempt.application.AttemptSubmitService;
import com.quizopia.backend.attempt.dto.SubmitRequest;
import com.quizopia.backend.attempt.dto.SubmitResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Submit endpoint (A3.2-4): {@code POST /api/attempts/{attemptId}/submit} — the 6th Day 7 REST endpoint,
 * extended with Day 8 auto-grading.
 *
 * <p>Authorization (STUDENT role + {@code ATTEMPT_SUBMIT} + ACTIVE profile), the status/idempotency
 * rules ({@code IMMUTABLE_CACHED_RESPONSE} / {@code ATTEMPT_ALREADY_SUBMITTED} /
 * {@code ATTEMPT_IDEMPOTENCY_CONFLICT}), the deadline check, and the atomic transition are enforced
 * in {@link AttemptSubmitService}. Day 8 adds: atomic auto-grading ({@link
 * com.quizopia.backend.grading.AttemptGradingService}) within the same transaction — one immutable
 * {@link com.quizopia.backend.attempt.domain.model.Grade} + {@link com.quizopia.backend.attempt.domain.model.GradeItem}s
 * are persisted before the AFTER_COMMIT realtime event fires. Returns HTTP 200 with the grading summary
 * (score, maxScore, percentage).
 */
@RestController
@RequestMapping("/api/attempts")
public class AttemptSubmitController {

    private final AttemptSubmitService submitService;

    public AttemptSubmitController(AttemptSubmitService submitService) {
        this.submitService = submitService;
    }

    @PostMapping("/{attemptId}/submit")
    public ResponseEntity<SubmitResponse> submit(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable Long attemptId,
                                                 @RequestBody SubmitRequest request) {
        SubmitResponse response = submitService.submitAttempt(Long.valueOf(jwt.getSubject()), attemptId, request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }
}
