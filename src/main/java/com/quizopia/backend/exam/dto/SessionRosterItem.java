package com.quizopia.backend.exam.dto;

/**
 * One student in a session's effective roster (class members ∪ direct
 * participants). User-based (issue #1 A4): {@code userId} is the member
 * identity, and realtime attempt events correlate on {@code userId}.
 *
 * <p>{@code studentProfileId} and {@code studentCode} are legacy placeholders
 * from the school-era model: the {@code student_profiles} table no longer
 * exists and both fields are always null. They are kept only so the response
 * shape stays stable for existing consumers.
 *
 * @param userId           the member user id
 * @param studentProfileId legacy placeholder, always null
 * @param studentCode      legacy placeholder, always null
 * @param displayName      the user's display name
 */
public record SessionRosterItem(
        Long userId,
        Long studentProfileId,
        String studentCode,
        String displayName) {
}
