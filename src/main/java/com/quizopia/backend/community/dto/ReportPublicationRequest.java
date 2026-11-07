package com.quizopia.backend.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/community/publications/{id}/reports body (ADR-006 §7).
 * {@code reasonCode} is the bounded V27 enum (checked in the service —
 * bean validation only guards shape); {@code details} is optional
 * free-form context, ≤500 chars.
 */
public record ReportPublicationRequest(
        @NotBlank String reasonCode,
        @Size(max = 500) String details) {
}
