package com.quizopia.backend.exam.dto;

import java.util.List;

/** Assigned classrooms for a session (GET/PUT /api/exam-sessions/{id}/classes). */
public record SessionClassesResponse(List<ClassSummary> items) {

    public record ClassSummary(Long id, String code, String name) {
    }
}
