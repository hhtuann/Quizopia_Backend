package com.quizopia.backend.community.dto;

/**
 * Public tag usage entry (ADR-006 §7): {@code usageCount} counts ONLY
 * publications passing the §4 visibility predicate — tags of
 * hidden/unpublished/archived/inactive sources never render. Ordered by
 * {@code usageCount} DESC, {@code label} ASC, top 100.
 */
public record PublicTagUsage(
        String label,
        long usageCount) {
}
