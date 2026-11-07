package com.quizopia.backend.community.dto;

import java.time.Instant;
import java.util.List;

/**
 * Moderator-console publication summary (queue rows, hide/restore
 * responses) — the FE #44 queue card shape ({@code publication.title /
 * authorName / categoryCode / categoryTitle / questionCount /
 * sectionCount / durationMinutes}, PublicPublicationCard-compatible
 * field names) plus moderator-privileged lifecycle context
 * ({@code status}, {@code moderationState}, {@code reusePolicy},
 * timestamps). Unlike the public card it renders HIDDEN/UNPUBLISHED
 * sources too, but exposes no exam id, no answers/question content and
 * no identity data beyond the explicitly public author credit
 * ({@code authorName} = the publish-time alias).
 */
public record ModerationPublicationSummary(
        Long publicationId,
        String title,
        String summary,
        String authorName,
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
        Instant updatedAt) {
}
