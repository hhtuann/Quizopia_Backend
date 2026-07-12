package com.quizopia.backend.question.dto;

import java.util.List;

/** Generic pagination response wrapper (Spring Data 0-based page). */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort
) {}
