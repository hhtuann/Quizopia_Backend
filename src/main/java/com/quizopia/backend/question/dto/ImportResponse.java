package com.quizopia.backend.question.dto;

import java.util.List;

/**
 * Response from the question-import persistence service. Returned inside an
 * HTTP 200 (partial success) — row errors are part of the body, not an
 * {@code ApiError}.
 *
 * @param totalRows    total data rows in the import file (from parser)
 * @param importedRows number of questions actually persisted
 * @param invalidRows  number of unique row numbers that had at least one error
 * @param errors       combined parser + DB-duplicate errors, sorted by rowNumber
 */
public record ImportResponse(
        int totalRows,
        int importedRows,
        int invalidRows,
        List<RowError> errors
) {}
