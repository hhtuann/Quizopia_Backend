package com.hhtuann.backend.question.template;

import com.hhtuann.backend.identity.repository.RolePermissionRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import com.hhtuann.backend.question.exception.QuestionErrorCode;
import com.hhtuann.backend.question.exception.QuestionException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Generates the Quizopia question-import Excel template entirely in memory.
 *
 * <p>The template has two sheets:
 * <ul>
 *   <li>{@code "Questions"} (index 0): a bold header row of 9 columns followed
 *       by 4 example rows (one per question type).</li>
 *   <li>{@code "Instructions"} (index 1): textual guidance.</li>
 * </ul>
 *
 * <p>The 9 columns are: {@code question_type, content, difficulty, option_a,
 * option_b, option_c, option_d, correct_answers, explanation}. All four
 * question types share {@code option_a..option_d} and {@code correct_answers};
 * the meaning of {@code correct_answers} depends on {@code question_type}
 * (see {@link com.hhtuann.backend.question.importer.ExcelQuestionParser}).
 *
 * <p>The {@code correct_answers} column is pre-formatted as Text so values such
 * as {@code 2.50} (NUMERIC_FILL) are preserved verbatim (no auto-conversion to
 * a number). The generated workbook contains no formulas, macros or password
 * protection.
 *
 * <p>Downloading the template requires the {@code QUESTION_CREATE} permission
 * but does <strong>not</strong> require a TeacherProfile (there is no
 * ownership/school scope for a static template).
 */
@Component
public class QuestionTemplateService {

    /** The fixed 9-column header, in order (matches ExcelQuestionParser.EXPECTED_HEADERS). */
    static final List<String> HEADERS = List.of(
            "question_type", "content", "difficulty",
            "option_a", "option_b", "option_c", "option_d",
            "correct_answers", "explanation");

    private static final int COL_CONTENT = 1;
    private static final int COL_CORRECT_ANSWERS = 7;
    private static final String TEXT_FORMAT = "@";

    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final Clock clock;

    public QuestionTemplateService(RolePermissionRepository rolePermissionRepository,
                                   UserRoleRepository userRoleRepository, Clock clock) {
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.clock = clock;
    }

    /**
     * Generates the template xlsx as a byte array.
     *
     * @return the workbook bytes
     */
    public byte[] generateTemplate() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            DataFormat dataFormat = workbook.createDataFormat();
            CellStyle textCellStyle = workbook.createCellStyle();
            textCellStyle.setDataFormat(dataFormat.getFormat(TEXT_FORMAT));

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(boldFont);

            Sheet questions = workbook.createSheet("Questions");
            Sheet instructions = workbook.createSheet("Instructions");

            buildQuestionsSheet(questions, headerStyle, textCellStyle);
            buildInstructionsSheet(instructions);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID,
                    "Failed to generate the import template");
        }
    }

    /**
     * Verifies the caller holds BOTH an active {@code TEACHER} role AND the
     * {@code QUESTION_CREATE} permission. Uses a single {@code Instant} for
     * both queries so role-expiry and permission-expiry are evaluated at the
     * same point in time. Does NOT require a TeacherProfile (the template has
     * no ownership or school scope).
     */
    public void checkPermission(Long userId) {
        Instant now = Instant.now(clock);
        // 1. Active TEACHER role — required independently of permission.
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
        if (!roles.contains("TEACHER")) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
        // 2. Effective QUESTION_CREATE permission.
        List<String> permissions = rolePermissionRepository
                .findEffectivePermissionCodesByUserId(userId, now);
        if (!permissions.contains("QUESTION_CREATE")) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
    }

    private void buildQuestionsSheet(Sheet sheet, CellStyle headerStyle, CellStyle textCellStyle) {
        // Header row (row 0)
        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADERS.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(HEADERS.get(i));
            cell.setCellStyle(headerStyle);
        }

        // Example rows (rows 1-4): one per question type.
        writeSingleChoiceExample(sheet, 1);
        writeMultipleChoiceExample(sheet, 2);
        writeTrueFalseExample(sheet, 3);
        writeNumericExample(sheet, 4);

        // Apply Text data format to the correct_answers column for a generous
        // range of rows (1..200), including example row 4. This guarantees
        // NUMERIC_FILL values are stored verbatim (e.g. "2.50" not 2.5).
        for (int r = 1; r <= 200; r++) {
            Row row = getOrCreateRow(sheet, r);
            Cell correctCell = row.getCell(COL_CORRECT_ANSWERS);
            if (correctCell == null) {
                correctCell = row.createCell(COL_CORRECT_ANSWERS);
            }
            correctCell.setCellStyle(textCellStyle);
        }

        // Reasonable column widths
        for (int i = 0; i < HEADERS.size(); i++) {
            sheet.setColumnWidth(i, 18 * 256);
        }
        sheet.setColumnWidth(COL_CONTENT, 30 * 256); // content
        sheet.setColumnWidth(COL_CORRECT_ANSWERS, 16 * 256);

        sheet.createFreezePane(0, 1);
    }

    private void writeSingleChoiceExample(Sheet sheet, int rowIdx) {
        Row r = getOrCreateRow(sheet, rowIdx);
        set(r, 0, "SINGLE_CHOICE");
        set(r, 1, "What is 2+2?");
        set(r, 2, "EASY");
        set(r, 3, "1");
        set(r, 4, "2");
        set(r, 5, "3");
        set(r, 6, "4");
        set(r, 7, "B");
        set(r, 8, "2 is the correct answer.");
    }

    private void writeMultipleChoiceExample(Sheet sheet, int rowIdx) {
        Row r = getOrCreateRow(sheet, rowIdx);
        set(r, 0, "MULTIPLE_CHOICE");
        set(r, 1, "Select even numbers");
        set(r, 2, "MEDIUM");
        set(r, 3, "2");
        set(r, 4, "3");
        set(r, 5, "4");
        set(r, 6, "5");
        set(r, 7, "AC");
    }

    private void writeTrueFalseExample(Sheet sheet, int rowIdx) {
        // The 4 options ARE the 4 statements; correct_answers = T/F per A-D.
        Row r = getOrCreateRow(sheet, rowIdx);
        set(r, 0, "TRUE_FALSE_MATRIX");
        set(r, 1, "Evaluate statements");
        set(r, 2, "MEDIUM");
        set(r, 3, "The sun is a star");
        set(r, 4, "Water boils at 50°C");
        set(r, 5, "Iron is heavier than cork");
        set(r, 6, "Sound travels faster than light");
        set(r, 7, "TFTF");
    }

    private void writeNumericExample(Sheet sheet, int rowIdx) {
        // Options left blank; correct_answers holds the numeric answer as text.
        // The Text style is applied to column 7 after the examples are written
        // (see buildQuestionsSheet), so "2.50" stays a string.
        Row r = getOrCreateRow(sheet, rowIdx);
        set(r, 0, "NUMERIC_FILL");
        set(r, 1, "What is 5/2? (answer with 2 decimal places)");
        set(r, 2, "EASY");
        set(r, 7, "2.50");
    }

    private static Row getOrCreateRow(Sheet sheet, int rowIdx) {
        Row row = sheet.getRow(rowIdx);
        return row != null ? row : sheet.createRow(rowIdx);
    }

    private void buildInstructionsSheet(Sheet sheet) {
        String[] lines = {
                "Quizopia Question Import Template (9 columns)",
                "",
                "Columns: question_type, content, difficulty, option_a, option_b, option_c, option_d, correct_answers, explanation.",
                "",
                "Rules:",
                "1. Only the 'Questions' sheet (first sheet) is read.",
                "2. Do not change, reorder or delete header columns.",
                "3. Do not use formulas in any cell. Formula cells invalidate the row.",
                "4. correct_answers is pre-formatted as Text. For NUMERIC_FILL, type it as text",
                "   (e.g. 2.50), exactly 4 characters, never as a number.",
                "5. question_type must be one of: SINGLE_CHOICE, MULTIPLE_CHOICE,",
                "   TRUE_FALSE_MATRIX, NUMERIC_FILL.",
                "6. SINGLE_CHOICE: options A-D required; correct_answers = one letter (e.g. B).",
                "7. MULTIPLE_CHOICE: options A-D required; correct_answers = the correct letters",
                "   concatenated without separators (e.g. ACD), at least two.",
                "8. TRUE_FALSE_MATRIX: options A-D are the 4 statements (all required);",
                "   correct_answers = 4 characters T/F for A-D in order (e.g. TFTF).",
                "9. NUMERIC_FILL: leave options blank; correct_answers = the 4-char answer (e.g. 2.50).",
                "10. difficulty is EASY, MEDIUM or HARD (optional).",
                "11. explanation is optional.",
                "12. Blank rows are ignored."
        };
        for (int i = 0; i < lines.length; i++) {
            Row row = sheet.createRow(i);
            row.createCell(0).setCellValue(lines[i]);
        }
        sheet.setColumnWidth(0, 100 * 256);
    }

    private static void set(Row row, int col, String value) {
        if (value == null) {
            return;
        }
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
    }
}
