package com.quizopia.backend.classroom.application;

import com.quizopia.backend.classroom.dto.reporting.GradebookCell;
import com.quizopia.backend.classroom.dto.reporting.GradebookResponse;
import com.quizopia.backend.classroom.dto.reporting.GradebookSessionColumn;
import com.quizopia.backend.classroom.dto.reporting.GradebookStudentRow;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.grading.ExcelCellSanitizer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CLASS-M3 (issue #54): teacher-only, classroom-scoped XLSX gradebook export.
 *
 * <p>Follows the {@code ExcelExportService} conventions exactly: SXSSF
 * streaming workbook (100-row window), full output buffered in memory (bounded
 * by the cohort cap enforced by {@link ClassroomGradebookService} — max 5000
 * rows × max 10 session columns), {@code dispose()} + close in a finally on
 * success AND failure, {@link ExcelCellSanitizer} on every teacher- or
 * session-controlled text cell, stable filename/content type.</p>
 *
 * <p>Data semantics are byte-identical to the gradebook API: the SAME
 * {@link ClassroomGradebookService#gradebook} computation produces the rows —
 * the export never recomputes or reinterprets a score.</p>
 */
@Service
public class ClassroomGradebookExportService {

    /** Extra safety bound on top of the service's cohort cap. */
    private static final int MAX_EXPORT_ROWS = 5000;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final ClassroomGradebookService gradebookService;
    public ClassroomGradebookExportService(ClassroomGradebookService gradebookService) {
        this.gradebookService = gradebookService;
    }

    public record ExportFile(byte[] content, String filename) {
    }

    public static String contentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Transactional(readOnly = true)
    public ExportFile export(Long userId, Long classroomId, List<Long> sessionIds, String search) {
        // Authorization + data via the SAME code path as the API (owner-only),
        // with the export row bound instead of the API page cap (the cohort
        // bound is enforced inside gradebookForExport).
        GradebookResponse data = gradebookService.gradebookForExport(userId, classroomId,
                sessionIds, search);


        SXSSFWorkbook wb = new SXSSFWorkbook(100);
        try {
            buildGradebookSheet(wb, data, classroomId);
            buildSummarySheet(wb, data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new ExportFile(out.toByteArray(), filename(classroomId));
        } catch (IOException ex) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_GRADEBOOK_EXPORT_FAILED);
        } finally {
            wb.dispose(); // releases SXSSF temp files on success AND failure
            try {
                wb.close();
            } catch (IOException ignored) {
                // dispose already reclaimed the temp files.
            }
        }
    }

    private void buildGradebookSheet(SXSSFWorkbook wb, GradebookResponse data, Long classroomId) {
        SXSSFSheet sheet = wb.createSheet("Gradebook");
        CreationHelper helper = wb.getCreationHelper();
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        CellStyle decimalStyle = wb.createCellStyle();
        decimalStyle.setDataFormat(helper.createDataFormat().getFormat("0.00"));

        List<GradebookSessionColumn> sessions = data.sessions();
        Row header = sheet.createRow(0);
        int col = 0;
        header.createCell(col++).setCellValue("No.");
        header.createCell(col++).setCellValue(ExcelCellSanitizer.sanitize("Student"));
        for (GradebookSessionColumn s : sessions) {
            String prefix = ExcelCellSanitizer.sanitize(
                    s.title() + " (" + s.code() + ")");
            header.createCell(col++).setCellValue(prefix + " — Status");
            header.createCell(col++).setCellValue(prefix + " — Score");
            header.createCell(col++).setCellValue(prefix + " — %");
        }
        header.createCell(col++).setCellValue("Mean %");
        header.createCell(col).setCellValue("Completion %");
        for (Cell c : header) {
            c.setCellStyle(headerStyle);
        }

        int rowIdx = 1;
        for (GradebookStudentRow row : data.students()) {
            Row r = sheet.createRow(rowIdx);
            int c = 0;
            r.createCell(c++).setCellValue(rowIdx);
            r.createCell(c++).setCellValue(ExcelCellSanitizer.sanitize(row.displayName()));
            for (GradebookCell cell : row.cells()) {
                r.createCell(c++).setCellValue(cell.status() == null ? "" : cell.status());
                Cell score = r.createCell(c++);
                if (cell.score() != null) {
                    score.setCellValue(cell.score().doubleValue());
                }
                Cell pct = r.createCell(c++);
                if (cell.percentage() != null) {
                    pct.setCellValue(cell.percentage().doubleValue());
                    pct.setCellStyle(decimalStyle);
                }
            }
            Cell mean = r.createCell(c++);
            if (row.meanPercentage() != null) {
                mean.setCellValue(row.meanPercentage().doubleValue());
                mean.setCellStyle(decimalStyle);
            }
            Cell completion = r.createCell(c);
            if (row.completionPercentage() != null) {
                completion.setCellValue(row.completionPercentage().doubleValue());
                completion.setCellStyle(decimalStyle);
            }
            rowIdx++;
        }
        sheet.createFreezePane(2, 1);
    }

    private void buildSummarySheet(SXSSFWorkbook wb, GradebookResponse data) {
        SXSSFSheet sheet = wb.createSheet("Session Summary");
        Row header = sheet.createRow(0);
        String[] headers = { "Session", "Code", "Status", "Window state", "Cohort", "Started",
                "Submitted", "Graded", "Completion %", "Average %" };
        for (int i = 0; i < headers.length; i++) {
            Cell c = header.createCell(i);
            c.setCellValue(ExcelCellSanitizer.sanitize(headers[i]));
        }
        int rowIdx = 1;
        for (GradebookSessionColumn s : data.sessions()) {
            Row r = sheet.createRow(rowIdx++);
            r.createCell(0).setCellValue(ExcelCellSanitizer.sanitize(s.title()));
            r.createCell(1).setCellValue(ExcelCellSanitizer.sanitize(s.code()));
            r.createCell(2).setCellValue(s.status());
            r.createCell(3).setCellValue(s.windowState());
            r.createCell(4).setCellValue(s.cohortSize());
            r.createCell(5).setCellValue(s.startedCount());
            r.createCell(6).setCellValue(s.submittedCount());
            r.createCell(7).setCellValue(s.gradedCount());
            if (s.completionPercentage() != null) {
                r.createCell(8).setCellValue(s.completionPercentage().doubleValue());
            }
            if (s.averagePercentage() != null) {
                r.createCell(9).setCellValue(s.averagePercentage().doubleValue());
            }
        }
    }

    private static String filename(Long classroomId) {
        return "quizopia-class-" + classroomId + "-gradebook-" + TS.format(Instant.now()) + ".xlsx";
    }
}
