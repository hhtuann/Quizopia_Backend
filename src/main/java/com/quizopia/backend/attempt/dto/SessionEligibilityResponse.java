package com.quizopia.backend.attempt.dto;

import java.time.Instant;

/**
 * Participant-facing pre-flight state for ONE exam session (P2-D, issue #20).
 *
 * <p>Returned by {@code GET /api/exam-sessions/{sessionId}/eligibility} — the
 * server-authorized state behind a shared session link. It answers "what can
 * THIS authenticated user do with THIS session right now" WITHOUT starting an
 * attempt and WITHOUT leaking creator-only metadata: the fields mirror the
 * learner-safe {@link AvailableSessionItem} (plus the session/exam titles the
 * listing already exposes to eligible users). No answer keys, options,
 * explanations, rosters or results are included; the structural no-leak guard
 * in {@code AttemptDataLeakStructuralTest} covers this DTO like every other
 * learner DTO.
 *
 * @param state                   lifecycle/eligibility state for the landing UI
 * @param canStartNow             startAttempt would succeed right now
 * @param canResume               an IN_PROGRESS attempt is resumable before its deadline
 * @param attemptsUsed            attempts this user has started (any status)
 * @param remainingAttempts       maxAttempts − used (Integer.MAX_VALUE when unlimited)
 * @param activeAttemptId         current IN_PROGRESS attempt, if any
 * @param activeAttemptDeadlineAt deadline of that attempt, if any
 */
public record SessionEligibilityResponse(
        Long sessionId,
        String state,
        String sessionStatus,
        String title,
        String examTitle,
        String visibility,
        Instant startsAt,
        Instant endsAt,
        Integer durationMinutes,
        Integer maxAttempts,
        int attemptsUsed,
        int remainingAttempts,
        Long activeAttemptId,
        Instant activeAttemptDeadlineAt,
        boolean canStartNow,
        boolean canResume,
        Instant serverTime) {

    /** Landing states (serialized by name). */
    public static final String STATE_CAN_START = "CAN_START";
    public static final String STATE_CAN_RESUME = "CAN_RESUME";
    public static final String STATE_NOT_YET_STARTED = "NOT_YET_STARTED";
    public static final String STATE_NO_ATTEMPTS_LEFT = "NO_ATTEMPTS_LEFT";
    public static final String STATE_ATTEMPT_EXPIRED = "ATTEMPT_EXPIRED";
    public static final String STATE_ENDED = "ENDED";
}
