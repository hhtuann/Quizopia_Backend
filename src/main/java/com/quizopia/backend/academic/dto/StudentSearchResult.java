package com.quizopia.backend.academic.dto;

public record StudentSearchResult(
        Long studentProfileId,
        String studentCode,
        String displayName,
        String username,
        String email
) {
}
