package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Comment report submission response (ADR-007 §5, #66): 201 on first
 * submission, 200 with the SAME body when an OPEN report already
 * existed (dedupe — no reporter-side state oracle). Carries no comment
 * data beyond the id and no identity data beyond the reporter's own
 * user id.
 */
public record CommunityCommentReportResponse(
        Long reportId,
        Long commentId,
        Long reporterUserId,
        String reasonCode,
        String details,
        String status,
        Instant createdAt) {
}
