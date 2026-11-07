package com.quizopia.backend.question.dto;

import java.util.List;

/**
 * Read-only preview of an Excel question-import workbook (P2-C UX follow-up):
 * the authoritative parser runs WITHOUT persisting anything. One row per
 * sheet row — READY rows carry the parsed content and correct answers; rows
 * with problems list their issues (same messages the import would report).
 *
 * @param totalRows  total data rows in the workbook
 * @param readyRows  rows that would be imported
 * @param rows       per-sheet-row preview
 */
public record ExcelImportPreviewResponse(
        int totalRows,
        int readyRows,
        List<PreviewRow> rows) {

    public record PreviewRow(
            int rowNumber,
            String questionType,
            String content,
            List<String> correctAnswers,
            String status,
            List<String> issues) {

        public static final String STATUS_READY = "READY";
        public static final String STATUS_INVALID = "INVALID";
    }
}
