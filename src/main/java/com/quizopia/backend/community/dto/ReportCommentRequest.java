package com.quizopia.backend.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/community/comments/{commentId}/reports body (ADR-007 §5,
 * #66) — same shape discipline as {@code ReportPublicationRequest}
 * (#42): the bounded five-value reason enum is re-checked in the
 * service; {@code details} is optional free-form context, ≤500 chars.
 */
public record ReportCommentRequest(
        @NotBlank String reasonCode,
        @Size(max = 500) String details) {
}
