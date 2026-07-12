package com.quizopia.backend.exam.dto;

import jakarta.validation.constraints.Positive;

/**
 * Optional request body for {@code POST /api/exams/{examId}/publish}.
 *
 * <p>{@code expectedVersionNumber} is the optimistic token: if present it MUST equal the
 * current DRAFT {@code ExamVersion.versionNumber}; a mismatch (or a concurrent publish that
 * already consumed the DRAFT) yields {@code EXAM_PUBLISH_CONFLICT} (409). Absent body or null
 * means "publish the current DRAFT".
 */
public record PublishExamRequest(
        @Positive Integer expectedVersionNumber
) {
}
