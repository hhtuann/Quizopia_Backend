package com.quizopia.backend.exam.dto;

import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code POST /api/exams/{examId}/versions} (create next DRAFT).
 *
 * <p>{@code cloneFromVersionNumber} is optional ({@code @Positive} allows null):
 * <ul>
 *   <li>non-null → clone that specific version (must be PUBLISHED, belong to the exam);</li>
 *   <li>null → clone the latest PUBLISHED version (by {@code versionNumber} DESC).</</li>
 * </ul>
 * Absent body is treated as null (clone latest PUBLISHED).
 */
public record CreateExamVersionRequest(
        @Positive Integer cloneFromVersionNumber
) {
}
