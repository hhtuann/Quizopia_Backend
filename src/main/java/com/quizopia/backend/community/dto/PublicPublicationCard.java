package com.quizopia.backend.community.dto;

import java.time.Instant;
import java.util.List;

/**
 * Public catalog card (ADR-006 §7 exact allowlist, issue #41): the ONLY
 * fields a guest may see. {@code authorName} is the explicit public alias
 * ({@code author_alias}) — never {@code display_name}, username, email or
 * the publisher user id. Composition data is limited to denormalized
 * counts; no question/option content, answers, exam ids or section ids
 * ever appear. Verified recursively by JSON redaction tests.
 */
public record PublicPublicationCard(
        Long publicationId,
        String title,
        String summary,
        String authorName,
        String categoryCode,
        String categoryTitle,
        List<String> tags,
        int questionCount,
        int sectionCount,
        int durationMinutes,
        Instant publishedAt,
        boolean allowFork) {
}
