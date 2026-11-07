package com.quizopia.backend.community.dto;

import java.time.Instant;
import java.util.List;

/**
 * One row of {@code GET /api/community/me/bookmarks} (ADR-006 §7,
 * issue #45). Two exact shapes, no other fields ever:
 *
 * <ul>
 *   <li>{@link Available} — the {@code PublicPublicationCard} allowlist
 *       fields plus {@code savedAt} and {@code unavailable:false};</li>
 *   <li>{@link Unavailable} — exactly {@code {publicationId, savedAt,
 *       unavailable:true}}: a bookmark whose source now fails the §4
 *       predicate keeps its place on the shelf but leaks NOTHING — no
 *       title, alias, category, tags, counts or any last-known
 *       content. Verified recursively by JSON field-set tests.</li>
 * </ul>
 *
 * <p>The {@code unavailable} flag is pinned by each record's compact
 * constructor, so no caller can build a lying entry.
 */
public sealed interface PublicBookmarkEntry {

    /** §4-visible source: card fields + savedAt + {@code unavailable:false}. */
    record Available(
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
            Instant savedAt,
            boolean unavailable) implements PublicBookmarkEntry {

        public Available {
            unavailable = false;
        }
    }

    /** Source failing the §4 predicate: exactly three fields, nothing else. */
    record Unavailable(
            Long publicationId,
            Instant savedAt,
            boolean unavailable) implements PublicBookmarkEntry {

        public Unavailable {
            unavailable = true;
        }
    }
}
