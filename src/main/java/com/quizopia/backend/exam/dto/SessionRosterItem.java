package com.quizopia.backend.exam.dto;

/**
 * One student in a session's effective roster (class members ∪ direct
 * participants). User-based (issue #1 A4) with the legacy profile id kept
 * for realtime-event correlation: the frozen STOMP envelope identifies
 * attempt events by {@code studentProfileId}, which every attempt-capable
 * member still has (attempt start requires an active student profile).
 *
 * @param userId           the member user id
 * @param studentProfileId legacy student_profiles.id (null for members
 *                         without a profile — they cannot start attempts)
 * @param studentCode      legacy academic code (e.g. STU00000001); may be null
 * @param displayName      the user's display name
 */
public record SessionRosterItem(
        Long userId,
        Long studentProfileId,
        String studentCode,
        String displayName) {
}
