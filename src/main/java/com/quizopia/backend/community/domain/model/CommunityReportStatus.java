package com.quizopia.backend.community.domain.model;

/**
 * {@code community_reports.status} (ADR-006 §3 V27). OPEN reports are
 * deduped per (reporter, publication) by the partial unique index;
 * moderators move a report to DISMISSED or RESOLVED exactly once.
 */
public enum CommunityReportStatus {
    OPEN,
    DISMISSED,
    RESOLVED
}
