package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Moderator report-queue row (ADR-006 §5/§7): report fields plus the
 * publication card summary nested under {@code publication} (the FE #44
 * assumption {@code {id, publicationId, publication:{card}}}).
 * Moderator surfaces expose internal user ids (reporter, handler) — an
 * intentional, audited privilege (§5); never username/email/phone.
 */
public record ModerationReportEntry(
        Long id,
        Long publicationId,
        ModerationPublicationSummary publication,
        Long reporterUserId,
        String reasonCode,
        String details,
        String status,
        Long handledByUserId,
        Instant handledAt,
        String resolutionNote,
        Instant createdAt) {
}
