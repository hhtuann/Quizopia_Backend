package com.hhtuann.backend.exam.dto;

/**
 * One student in a session's effective roster (direct participants ∪ class
 * members). Used by the teacher live-monitor to resolve a realtime event's
 * {@code studentProfileId} → {@code studentCode + displayName}.
 *
 * @param studentProfileId the student_profiles.id (matches the realtime event's studentProfileId)
 * @param studentCode      e.g. STU00000001
 * @param displayName      the user's display name
 */
public record SessionRosterItem(
        Long studentProfileId,
        String studentCode,
        String displayName) {
}
