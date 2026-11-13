package com.quizopia.backend.exam.dto;

import java.time.Instant;

public record ExamSessionDetailResponse(
        Long id,
        Long examId,
        String examCode,
        String code,
        String title,
        String status,
        Instant startsAt,
        Instant endsAt,
        Integer maxAttempts,
        Integer durationMinutes,
        Instant openedAt,
        Instant closedAt,
        long participantCount,
        Integer version,
        Instant createdAt,
        String visibility,
        /** Monitoring policy projection incl. derived cameraRequired/frozen (P4-A1). */
        MonitoringPolicyResponse monitoring
) {

    /** Legacy 16-arg shape (pre-P4-A callers): monitoring projection = all OFF. */
    public ExamSessionDetailResponse(Long id, Long examId, String examCode, String code,
            String title, String status, Instant startsAt, Instant endsAt, Integer maxAttempts,
            Integer durationMinutes, Instant openedAt, Instant closedAt, long participantCount,
            Integer version, Instant createdAt, String visibility) {
        this(id, examId, examCode, code, title, status, startsAt, endsAt, maxAttempts,
                durationMinutes, openedAt, closedAt, participantCount, version, createdAt,
                visibility, MonitoringPolicyResponse.off());
    }
}
