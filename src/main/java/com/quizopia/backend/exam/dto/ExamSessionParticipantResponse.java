package com.quizopia.backend.exam.dto;

import java.time.Instant;

/**
 * Participant row response (user-based since V17, issue #4 P0-R9):
 * {@code userId} is the primary identity; {@code studentProfileId}/
 * {@code studentCode} are legacy provenance (null for profile-less
 * participants); {@code displayName} is always resolved from the user.
 */
public record ExamSessionParticipantResponse(
        Long id,
        Long userId,
        Long studentProfileId,
        String studentCode,
        String displayName,
        String status,
        Instant addedAt,
        Instant blockedAt
) {

    /** Backward-compatible constructor for pre-V17 callers (userId derived from the profile). */
    public ExamSessionParticipantResponse(Long id, Long studentProfileId, String studentCode,
            String displayName, String status, Instant addedAt, Instant blockedAt) {
        this(id, null, studentProfileId, studentCode, displayName, status, addedAt, blockedAt);
    }
}
