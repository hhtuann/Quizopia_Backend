package com.quizopia.backend.exam.dto;

import java.time.Instant;

/**
 * CLASS-M2 (issue #52): one learner-safe assigned session row on the member's
 * classroom view ({@code GET /api/classrooms/joined/{classId}/sessions}).
 * DRAFT sessions are NEVER included; attempt eligibility/start/resume/own
 * result decisions are delegated to the existing eligibility/attempt
 * services via the {@code /sessions/{sessionId}} landing — no attempt
 * counters or other learners' data are copied here.
 */
public record LearnerClassroomSessionItem(
        Long sessionId,
        String code,
        String title,
        String status,
        Instant startsAt,
        Instant endsAt,
        String examTitle
) {
}
