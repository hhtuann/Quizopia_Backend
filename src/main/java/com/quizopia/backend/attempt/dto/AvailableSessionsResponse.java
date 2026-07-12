package com.quizopia.backend.attempt.dto;

import java.time.Instant;
import java.util.List;

/** Response for GET /api/exam-sessions/available — per frozen contract §8.1. */
public record AvailableSessionsResponse(List<AvailableSessionItem> items, Instant serverTime) {

    public record AvailableSessionItem(
            Long sessionId, String code, String title, String sessionStatus,
            ExamInfo exam, Instant startsAt, Instant endsAt,
            Integer durationMinutes, Integer maxAttempts,
            int attemptsUsed, int remainingAttempts,
            Long activeAttemptId, Instant activeAttemptDeadlineAt,
            boolean canStartNow, boolean canResume,
            String visibility) {

        public record ExamInfo(Long examId, String title, String subjectName) {}
    }
}
