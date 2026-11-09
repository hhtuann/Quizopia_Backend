package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Comment-reports queue item (ADR-007 §5, #66): the report row plus
 * the reported {@link ModerationCommentSummary comment block} and the
 * parent publication's card summary (reused projection). Reporter and
 * handler render as bare internal user ids — moderator privilege
 * (ADR-006 §5 parity); nothing here is ever public.
 */
public record ModerationCommentReportEntry(
        Long id,
        Long commentId,
        ModerationCommentSummary comment,
        ModerationPublicationSummary publication,
        Long reporterUserId,
        String reasonCode,
        String details,
        String status,
        Long handledBy,
        Instant handledAt,
        String resolutionNote,
        Instant createdAt) {
}
