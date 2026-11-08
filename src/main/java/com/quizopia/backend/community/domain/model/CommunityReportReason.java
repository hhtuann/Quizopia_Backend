package com.quizopia.backend.community.domain.model;

import java.util.Locale;

/**
 * Bounded {@code community_reports.reason_code} catalog (ADR-006 §3
 * V27 CHECK constraint). Free-form context belongs in {@code details}.
 */
public enum CommunityReportReason {
    INAPPROPRIATE_CONTENT,
    COPYRIGHT,
    MISLEADING,
    SPAM,
    OTHER;

    /** Case-insensitive parse; callers map failure to 400 validation. */
    public static CommunityReportReason parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
