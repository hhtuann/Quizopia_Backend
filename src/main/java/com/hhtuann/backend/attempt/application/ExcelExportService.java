package com.hhtuann.backend.attempt.application;

import com.hhtuann.backend.attempt.dto.QuestionStatisticsItem;
import com.hhtuann.backend.attempt.dto.ScoreDistributionBucket;
import com.hhtuann.backend.attempt.dto.SessionResultItem;
import com.hhtuann.backend.attempt.dto.SessionStatisticsResponse;
import com.hhtuann.backend.attempt.exception.AttemptErrorCode;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.grading.ExcelCellSanitizer;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Day 8 XLSX export — generates a two-sheet workbook (Results + Statistics)
 * using SXSSF for memory safety.
 * Formula-injection protection via {@link ExcelCellSanitizer} on all
 * user-controlled strings.
 * Authorization is checked BEFORE workbook creation (delegated to
 * SessionResultService).
 */
@Service
@Transactional(readOnly = true)
public class ExcelExportService {

    private static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final SessionResultService sessionResultService;
    private final SessionStatisticsService statisticsService;

    public ExcelExportService(SessionResultService sessionResultService,
            SessionStatisticsService statisticsService) {
        this.sessionResultService = sessionResultService;
        this.statisticsService = statisticsService;
    }

    public static String contentType() {
        return CONTENT_TYPE;
    }

    /**
     * Protected workbook factory (testability seam). The {@code finally} block in
     * {@link #export}
     * always calls {@code dispose()} + {@code close()} on the instance returned
     * here, on both success
     * and failure. Overridable so tests can substitute a tracking workbook to prove
     * cleanup.
     */
    protected SXSSFWorkbook createWorkbook() {
        return new SXSSFWorkbook(100);
    }

    public static String filename(Long sessionId) {
        String ts = Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return "quizopia-session-" + sessionId + "-results-" + ts + ".xlsx";
    }

    /**
     * Generates the XLSX bytes. Authorization must be checked by the caller before
     * invoking.
     */
    public byte[] export(Long userId, String primaryRole, Long sessionId) {
        List<SessionResultItem> results = sessionResultService.getAllBestResults(userId, primaryRole, sessionId);
        SessionStatisticsResponse stats = statisticsService.getStatistics(userId, primaryRole, sessionId);
        SXSSFWorkbook wb = createWorkbook();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataFormat df = wb.createDataFormat();
            CellStyle numericStyle = wb.createCellStyle();
            numericStyle.setDataFormat(df.getFormat("0.00"));
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(df.getFormat("yyyy-mm-dd hh:mm:ss"));

            buildResultsSheet(wb, results, numericStyle, dateStyle);
            buildStatisticsSheet(wb, stats, numericStyle);
            wb.write(out);
            return out.toByteArray();
        } catch (AttemptException e) {
            throw e;
        } catch (Exception e) {
            throw new AttemptException(AttemptErrorCode.EXPORT_FAILED_INTERNAL);
        } finally {
            wb.dispose();
            try {
                wb.close();
            } catch (Exception ignored) {
                /* best-effort close after dispose */ }
        }
    }

    private void buildResultsSheet(SXSSFWorkbook wb, List<SessionResultItem> results,
            CellStyle numeric, CellStyle date) {
        Sheet s = wb.createSheet("Results");
        String[] headers = { "No.", "Student Code", "Student Name", "Best Attempt ID", "Attempt Count",
                "Submitted At", "Score", "Max Score", "Percentage", "Status" };
        Row hr = s.createRow(0);
        for (int i = 0; i < headers.length; i++)
            hr.createCell(i).setCellValue(headers[i]);
        s.createFreezePane(0, 1);
        int[] widths = { 6, 20, 30, 16, 14, 24, 10, 10, 12, 14 };
        for (int i = 0; i < widths.length; i++)
            s.setColumnWidth(i, widths[i] * 256);
        for (int i = 0; i < results.size(); i++) {
            SessionResultItem r = results.get(i);
            Row row = s.createRow(i + 1);
            int c = 0;
            row.createCell(c++).setCellValue(i + 1);
            setStringCell(row, c++, ExcelCellSanitizer.sanitize(r.studentCode()));
            setStringCell(row, c++, ExcelCellSanitizer.sanitize(r.displayName()));
            row.createCell(c++).setCellValue(r.bestAttemptId() != null ? r.bestAttemptId() : 0);
            row.createCell(c++).setCellValue(r.attemptCount());
            if (r.submittedAt() != null) {
                var cell = row.createCell(c);
                cell.setCellValue(java.util.Date.from(r.submittedAt()));
                cell.setCellStyle(date);
                c++;
            } else {
                row.createCell(c++);
            }
            setNumericCell(row, c++, r.score(), numeric);
            setNumericCell(row, c++, r.maxScore(), numeric);
            setNumericCell(row, c++, r.percentage(), numeric);
            setStringCell(row, c++, r.gradeStatus());
        }
    }

    private void buildStatisticsSheet(SXSSFWorkbook wb, SessionStatisticsResponse stats, CellStyle numeric) {
        Sheet s = wb.createSheet("Statistics");
        int rowIdx = 0;
        Row r0 = s.createRow(rowIdx++);
        r0.createCell(0).setCellValue("Metric");
        r0.createCell(1).setCellValue("Value");
        addStat(s, rowIdx++, "Best Result Count", String.valueOf(stats.bestResultCount()));
        addStat(s, rowIdx++, "Total Attempt Count", String.valueOf(stats.totalAttemptCount()));
        addStat(s, rowIdx++, "Started Students", String.valueOf(stats.startedStudentCount()));
        addStat(s, rowIdx++, "Submitted Students", String.valueOf(stats.submittedStudentCount()));
        addStat(s, rowIdx++, "Graded Students", String.valueOf(stats.gradedStudentCount()));
        addStat(s, rowIdx++, "Average Score",
                stats.averageScore() != null ? stats.averageScore().toPlainString() : "—");
        addStat(s, rowIdx++, "Average Percentage",
                stats.averagePercentage() != null ? stats.averagePercentage().toPlainString() : "—");
        addStat(s, rowIdx++, "Minimum Score",
                stats.minimumScore() != null ? stats.minimumScore().toPlainString() : "—");
        addStat(s, rowIdx++, "Maximum Score",
                stats.maximumScore() != null ? stats.maximumScore().toPlainString() : "—");
        addStat(s, rowIdx++, "Median Percentage",
                stats.medianPercentage() != null ? stats.medianPercentage().toPlainString() : "—");
        rowIdx++; // blank row
        // Distribution
        Row dh = s.createRow(rowIdx++);
        dh.createCell(0).setCellValue("Score Range");
        dh.createCell(1).setCellValue("Count");
        for (ScoreDistributionBucket b : stats.distribution()) {
            Row br = s.createRow(rowIdx++);
            br.createCell(0)
                    .setCellValue("[" + b.lowerBound() + ", " + b.upperBound() + (b.upperInclusive() ? "]" : ")"));
            br.createCell(1).setCellValue(b.count());
        }
        rowIdx++; // blank row
        // Per-question
        if (!stats.perQuestionStatistics().isEmpty()) {
            Row qh = s.createRow(rowIdx++);
            qh.createCell(0).setCellValue("Question ID");
            qh.createCell(1).setCellValue("Type");
            qh.createCell(2).setCellValue("Max Score");
            qh.createCell(3).setCellValue("Answered");
            qh.createCell(4).setCellValue("Correct");
            qh.createCell(5).setCellValue("Incorrect");
            qh.createCell(6).setCellValue("Unanswered");
            qh.createCell(7).setCellValue("Correct Rate");
            qh.createCell(8).setCellValue("Avg Awarded");
            for (QuestionStatisticsItem q : stats.perQuestionStatistics()) {
                Row qr = s.createRow(rowIdx++);
                qr.createCell(0).setCellValue(q.examQuestionId() != null ? q.examQuestionId() : 0);
                qr.createCell(1).setCellValue(ExcelCellSanitizer.sanitize(q.questionType()));
                setNumericCell(qr, 2, q.maxScore(), numeric);
                qr.createCell(3).setCellValue(q.answeredCount());
                qr.createCell(4).setCellValue(q.correctCount());
                qr.createCell(5).setCellValue(q.incorrectCount());
                qr.createCell(6).setCellValue(q.unansweredCount());
                setNumericCell(qr, 7, q.correctRate(), numeric);
                setNumericCell(qr, 8, q.averageAwardedScore(), numeric);
            }
        }
        s.setColumnWidth(0, 40 * 256);
        s.setColumnWidth(1, 20 * 256);
    }

    private void addStat(Sheet s, int rowIdx, String metric, String value) {
        Row r = s.createRow(rowIdx);
        r.createCell(0).setCellValue(metric);
        r.createCell(1).setCellValue(value);
    }

    private static void setStringCell(Row row, int col, String value) {
        if (value != null)
            row.createCell(col).setCellValue(value);
        else
            row.createCell(col);
    }

    private static void setNumericCell(Row row, int col, BigDecimal value, CellStyle style) {
        if (value != null) {
            var cell = row.createCell(col);
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(style);
        } else
            row.createCell(col);
    }
}
