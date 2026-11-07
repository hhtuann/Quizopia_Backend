package com.quizopia.backend.classroom.dto;

import java.util.List;

/**
 * User-based student search ({@code GET /api/students/search}) — feeds the
 * classroom member-add flow. Results are platform users holding the STUDENT
 * role; minimal fields only (no profile data — the school-era profile is
 * gone).
 */
public record StudentSearchResponse(List<StudentSearchResult> items) {

    public record StudentSearchResult(
            Long userId,
            String displayName,
            String username,
            String email
    ) {
    }
}
