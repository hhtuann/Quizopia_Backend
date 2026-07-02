package com.hhtuann.backend.exam.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code PUT /api/exams/{examId}/draft/composition}.
 *
 * <p>Replaces the entire DRAFT composition. The client sends only structural
 * data — source question selection, teacher point overrides, section layout.
 * The backend resolves + pins the source question version and snapshots
 * content/options/answer_key/metadata. The client never sends snapshot fields
 * (sourceQuestionVersionId, content, options, answerKey, metadata, etc.).
 *
 * <p>{@code expectedVersionNumber} is the optimistic token: it MUST equal the
 * current DRAFT {@code ExamVersion.versionNumber}; a mismatch yields
 * {@code EXAM_CONCURRENT_MODIFICATION} (409). It is NOT the JPA {@code @Version}
 * on {@code Exam} and NOT {@code Exam.currentVersionNumber}.
 *
 * <p>{@code sections} is required but may be empty (clears the DRAFT
 * composition). Each section must carry a non-null questions list (may be
 * empty — publish, not save, enforces ≥1 question per section).
 */
public record UpdateDraftCompositionRequest(
        @NotNull @Positive Integer expectedVersionNumber,
        @Positive Integer durationMinutes,
        String instructions,
        @NotNull @Valid List<CompositionSectionRequest> sections
) {

    public record CompositionSectionRequest(
            @NotNull @jakarta.validation.constraints.Min(0) Integer position,
            @NotBlank @Size(max = 255) String title,
            String instructions,
            @NotNull @Valid List<CompositionQuestionRequest> questions
    ) {
    }

    public record CompositionQuestionRequest(
            @NotNull @Positive Long sourceQuestionId,
            @NotNull @jakarta.validation.constraints.Min(0) Integer position,
            @Positive java.math.BigDecimal defaultPoints
    ) {
    }
}
