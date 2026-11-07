package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Report submission response (ADR-006 §7): 201 on first submission, 200
 * with the SAME body when an OPEN report already existed (dedupe — no
 * reporter-side state oracle). Carries no publication data beyond the
 * id and no identity data beyond the reporter's own user id.
 */
public record CommunityReportResponse(
        Long reportId,
        Long publicationId,
        Long reporterUserId,
        String reasonCode,
        String details,
        String status,
        Instant createdAt) {
}
