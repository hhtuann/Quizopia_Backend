package com.hhtuann.backend.exam.dto;

import java.util.List;

/**
 * Body for PUT /api/exam-sessions/{sessionId}/classes — replaces the assigned
 * classrooms (delete old + insert new). Empty/null clears the assignment.
 */
public record AssignSessionClassesRequest(
        List<Long> classroomIds
) {
}
