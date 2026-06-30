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
 *   <li>{@code "Questions"} (index 0): a bold header row of 23 columns followed
 *       by 4 example rows (one per question type).</li>
 *   <li>{@code "Instructions"} (index 1): textual guidance.</li>
 * </ul>
 *
 * <p>The {@code numeric_answer} column is pre-formatted as Text so that values
 * such as {@code 2.50} are preserved verbatim (no auto-conversion to a number).
 * The generated workbook contains no formulas, macros or password protection.
 *
 * <p>Downloading the template requires the {@code QUESTION_CREATE} permission
 * but does <strong>not</strong> require a TeacherProfile (there is no
 * ownership/school scope for a static template).
 */
@Component
public class QuestionTemplateService {

    /** The fixed 23-column header, in order. */
    static final List<String> HEADERS = List.of(
            "question_code", "question_type", "content", "default_points", "difficulty",
            "option_a", "option_b", "option_c", "option_d", "option_e", "option_f",
            "correct_answers",
            "statement_a", "statement_a_answer",
            "statement_b", "statement_b_answer",
            "statement_c", "statement_c_answer",
            "statement_d", "statement_d_answer",
            "numeric_answer", "rounding_instruction", "explanation");

    private static final int COL_NUMERIC_ANSWER = 20;
    private static final String TEXT_FORMAT = "@";

    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final Clock clock;

    public QuestionTemplateService(RolePermissionRepository rolePermissionRepository,
                                   UserRoleRepository userRoleRepository,
                                   Clock clock) {
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

    private void buildQuestionsSheet(Sheet sheet, CellStyle headerStyle,
                                     CellStyle textCellStyle) {
        // Header row (row 0)
        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADERS.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(HEADERS.get(i));
            cell.setCellStyle(headerStyle);
        }

        // Example rows (rows 1-4)
        writeSingleChoiceExample(sheet, 1);
        writeMultipleChoiceExample(sheet, 2);
        writeTrueFalseExample(sheet, 3);
        writeNumericExample(sheet, 4);

        // Apply Text data format to the numeric_answer column for a generous
        // range of rows (1..200), including example row 4. This guarantees
        // numeric_answer values are stored verbatim (e.g. "2.50" not 2.5).
        for (int r = 1; r <= 200; r++) {
            Row row = getOrCreateRow(sheet, r);
            Cell numericCell = row.getCell(COL_NUMERIC_ANSWER);
            if (numericCell == null) {
                numericCell = row.createCell(COL_NUMERIC_ANSWER);
            }
            numericCell.setCellStyle(textCellStyle);
        }

        // Reasonable column widths
        for (int i = 0; i < HEADERS.size(); i++) {
            sheet.setColumnWidth(i, 18 * 256);
        }
        sheet.setColumnWidth(2, 30 * 256); // content
        sheet.setColumnWidth(COL_NUMERIC_ANSWER, 16 * 256);

        sheet.createFreezePane(0, 1);
    }

    private void writeSingleChoiceExample(Sheet sheet, int rowIdx) {
        Row r = getOrCreateRow(sheet, rowIdx);
        set(r, 0, "EX-SINGLE");
        set(r, 1, "SINGLE_CHOICE");
        set(r, 2, "What is 2+2?");
        set(r, 3, "1");
        set(r, 4, "EASY");
        set(r, 5, "1");
        set(r, 6, "2");
        set(r, 7, "3");
        set(r, 8, "4");
        set(r, 11, "B");
        set(r, 22, "2 is the correct answer.");
    }

    private void writeMultipleChoiceExample(Sheet sheet, int rowIdx) {
        Row r = getOrCreateRow(sheet, rowIdx);
        set(r, 0, "EX-MULTI");
        set(r, 1, "MULTIPLE_CHOICE");
        set(r, 2, "Select even numbers");
        set(r, 3, "2");
        set(r, 4, "MEDIUM");
        set(r, 5, "2");
        set(r, 6, "3");
        set(r, 7, "4");
        set(r, 8, "5");
        set(r, 11, "A,C");
    }

    private void writeTrueFalseExample(Sheet sheet, int rowIdx) {
        Row r = getOrCreateRow(sheet, rowIdx);
        set(r, 0, "EX-TF");
        set(r, 1, "TRUE_FALSE_MATRIX");
        set(r, 2, "Evaluate statements");
        set(r, 3, "3");
        set(r, 4, "MEDIUM");
        set(r, 12, "The sun is a star");
        set(r, 13, "TRUE");
        set(r, 14, "Water boils at 50°C");
        set(r, 15, "FALSE");
        set(r, 16, "Iron is heavier than cork");
        set(r, 17, "TRUE");
        set(r, 18, "Sound travels faster than light");
        set(r, 19, "FALSE");
    }

    private void writeNumericExample(Sheet sheet, int rowIdx) {
        Row r = getOrCreateRow(sheet, rowIdx);
        set(r, 0, "EX-NUM");
        set(r, 1, "NUMERIC_FILL");
        set(r, 2, "What is 5/2?");
        set(r, 3, "1");
        set(r, 4, "EASY");
        // numeric_answer MUST be a STRING cell. The Text style is applied to
        // this column after the examples are written (see buildQuestionsSheet).
        r.createCell(COL_NUMERIC_ANSWER).setCellValue("2.50");
        set(r, 21, "Round to 2 decimal places");
    }

    private static Row getOrCreateRow(Sheet sheet, int rowIdx) {
        Row row = sheet.getRow(rowIdx);
        return row != null ? row : sheet.createRow(rowIdx);
    }

    private void buildInstructionsSheet(Sheet sheet) {
        String[] lines = {
                "Quizopia Question Import Template",
                "",
                "Rules:",
                "1. Only the 'Questions' sheet (first sheet) is read.",
                "2. Do not change, reorder or delete header columns.",
                "3. Do not use formulas in any cell. Formula cells invalidate the row.",
                "4. numeric_answer MUST be typed as text (the column is pre-formatted as Text).",
                "   Do NOT type it as a number. Enter exactly 4 characters, e.g. 2.50 or 2,50.",
                "5. question_type must be one of: SINGLE_CHOICE, MULTIPLE_CHOICE,",
                "   TRUE_FALSE_MATRIX, NUMERIC_FILL.",
                "6. SINGLE_CHOICE: options A-D required (E-F optional, no gaps),",
                "   exactly one correct answer.",
                "7. MULTIPLE_CHOICE: options A-D required, at least two correct answers.",
                "8. TRUE_FALSE_MATRIX: statements A-D required, each answered TRUE or FALSE.",
                "9. NUMERIC_FILL: numeric_answer (4 chars, text) + rounding_instruction required.",
                "10. question_code must be unique within the file (case-insensitive).",
                "11. Blank rows are ignored."
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
        // Default style is General; the numeric_answer column keeps its Text
        // style (applied above for rows 1..200) only when this cell was
        // created there. For correctness, non-numeric columns are plain text.
        cell.setCellValue(value);
    }
}
