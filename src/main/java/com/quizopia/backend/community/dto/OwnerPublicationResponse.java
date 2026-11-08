package com.quizopia.backend.community.dto;

import java.time.Instant;
import java.util.List;

/**
 * Owner-facing publication DTO (ADR-006 §7 owner list/detail shape for
 * #40, completed by #42). Contains ONLY publication metadata plus
 * taxonomy labels and the denormalized composition <em>counts</em> —
 * never question/option content, answers, or any identity data beyond
 * the explicit public alias. {@code openReportCount} counts OPEN
 * community reports (aggregate only — no reporter data leaves).
 */
public record OwnerPublicationResponse(
        Long publicationId,
        Long examId,
        String title,
        String summary,
        String authorAlias,
        Long categoryId,
        String categoryCode,
        String categoryTitle,
        List<String> tags,
        String status,
        String moderationState,
        String reusePolicy,
        int questionCount,
        int sectionCount,
        int durationMinutes,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        int version,
        long openReportCount) {
}
