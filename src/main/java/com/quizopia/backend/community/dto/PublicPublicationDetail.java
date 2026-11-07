package com.quizopia.backend.community.dto;

import java.time.Instant;
import java.util.List;

/**
 * Public detail (ADR-006 §7): the card allowlist plus exactly one extra
 * field, {@code updatedAt}. Invisible/absent publications are 404
 * anti-enumeration (identical body to a random id).
 */
public record PublicPublicationDetail(
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
        boolean allowFork,
        Instant updatedAt) {

    public PublicPublicationDetail(PublicPublicationCard card, Instant updatedAt) {
        this(card.publicationId(), card.title(), card.summary(), card.authorName(),
                card.categoryCode(), card.categoryTitle(), card.tags(),
                card.questionCount(), card.sectionCount(), card.durationMinutes(),
                card.publishedAt(), card.allowFork(), updatedAt);
    }
}
