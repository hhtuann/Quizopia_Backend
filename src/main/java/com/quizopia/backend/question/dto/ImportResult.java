package com.quizopia.backend.question.dto;

import java.util.List;

/**
 * Result of parsing an Excel import file. Contains valid rows (for B2.2
 * persistence) and per-row errors (for the partial-success response).
 */
public record ImportResult(
        int totalRows,
        List<ValidQuestionRow> validRows,
        List<RowError> errors
) {}
