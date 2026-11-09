package com.quizopia.backend.classroom.dto.reporting;

import java.util.List;

/**
 * CLASS-M3 (issue #54): the gradebook matrix page. {@code historyCoverage}
 * is {@code SINCE_MIGRATION}: membership periods exist only from the V30
 * backfill (current members) forward — pre-migration departures are not
 * fabricated (issue #54 design gate 4).
 */
public record GradebookResponse(
        List<GradebookSessionColumn> sessions,
        List<GradebookStudentRow> students,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort,
        String historyCoverage) {
}
