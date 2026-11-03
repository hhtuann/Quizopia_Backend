package com.quizopia.backend.exam.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * Optional request body for {@code POST /api/exams/{examId}/publish}.
 *
 * <p>{@code expectedExamVersion} is the optimistic token: if present it MUST
 * equal the current JPA {@code @Version} of the exam row (starting at 0); a
 * mismatch (or a concurrent publish) yields {@code EXAM_PUBLISH_CONFLICT}
 * (409). Absent body or null means "publish the current DRAFT".
 */
public record PublishExamRequest(
        @PositiveOrZero Integer expectedExamVersion
) {
}
