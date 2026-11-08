package com.quizopia.backend.classroom.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Roster entry projection. Sensitive fields (dob, phones, raw email) are
 * decrypted ONLY for the classroom owner or the linked learner; the
 * matching email is never returned to other callers.
 */
public record RosterEntryResponse(
        Long id,
        Long classroomId,
        String status,
        Long userId,
        String fullName,
        String studentNumber,
        String gender,
        LocalDate dob,
        String phone,
        String parentPhone,
        String email,
        Instant createdAt,
        Instant linkedAt,
        String classroomName,
        String teacherDisplayName
) {
}
