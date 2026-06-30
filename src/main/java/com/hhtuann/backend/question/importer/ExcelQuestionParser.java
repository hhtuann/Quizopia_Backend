package com.hhtuann.backend.question.importer;

import com.hhtuann.backend.question.domain.model.QuestionDifficulty;
import com.hhtuann.backend.question.domain.model.QuestionType;
import com.hhtuann.backend.question.dto.ImportResult;
import com.hhtuann.backend.question.dto.RowError;
import com.hhtuann.backend.question.dto.ValidQuestionRow;
import com.hhtuann.backend.question.exception.QuestionErrorCode;
import com.hhtuann.backend.question.exception.QuestionException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses a Quizopia question-import xlsx workbook into an {@link ImportResult}.
 *
 * <p>This component performs <strong>structural and content validation only</strong>
 * — it never touches a repository (database duplicate detection is deferred to
 * Batch B2.2). Row-level errors are collected rather than aborting the workbook;
 * only unrecoverable structural problems (corrupt file, missing sheet, wrong
 * header) throw a {@link QuestionException}.
 *
 * <p>Strict rules enforced:
 * <ul>
 *   <li>Only the {@code "Questions"} sheet (index 0) is read.</li>
 *   <li>The header row must match exactly 23 columns in a fixed order.</li>
 *   <li>{@code numeric_answer} must be a STRING cell — never NUMERIC, never a
 *       FORMULA, never trimmed, never read through a DataFormatter.</li>
 *   <li>Any FORMULA cell in a data field invalidates its row.</li>
 * </ul>
 */
@Component
public class ExcelQuestionParser {

    /** Exact header order (lower-cased on read for comparison). 23 columns. */
    public static final List<String> EXPECTED_HEADERS = List.of(
            "question_code", "question_type", "content", "default_points", "difficulty",
            "option_a", "option_b", "option_c", "option_d", "option_e", "option_f",
            "correct_answers",
            "statement_a", "statement_a_answer",
            "statement_b", "statement_b_answer",
            "statement_c", "statement_c_answer",
            "statement_d", "statement_d_answer",
            "numeric_answer", "rounding_instruction", "explanation");

    // Column indexes (0-based)
    private static final int COL_QUESTION_CODE = 0;
    private static final int COL_QUESTION_TYPE = 1;
    private static final int COL_CONTENT = 2;
    private static final int COL_DEFAULT_POINTS = 3;
    private static final int COL_DIFFICULTY = 4;
    private static final int COL_OPTION_A = 5;
    private static final int COL_OPTION_B = 6;
    private static final int COL_OPTION_C = 7;
    private static final int COL_OPTION_D = 8;
    private static final int COL_OPTION_E = 9;
    private static final int COL_OPTION_F = 10;
    private static final int COL_CORRECT_ANSWERS = 11;
    private static final int COL_STATEMENT_A = 12;
    private static final int COL_STATEMENT_A_ANSWER = 13;
    private static final int COL_STATEMENT_B = 14;
    private static final int COL_STATEMENT_B_ANSWER = 15;
    private static final int COL_STATEMENT_C = 16;
    private static final int COL_STATEMENT_C_ANSWER = 17;
    private static final int COL_STATEMENT_D = 18;
    private static final int COL_STATEMENT_D_ANSWER = 19;
    private static final int COL_NUMERIC_ANSWER = 20;
    private static final int COL_ROUNDING_INSTRUCTION = 21;
    private static final int COL_EXPLANATION = 22;

    private static final int HEADER_COUNT = EXPECTED_HEADERS.size(); // 23

    private static final String KEY_PATTERN = "^[A-F]$";
    private static final String NUMERIC_PATTERN = "^-?[0-9]+([,.][0-9]+)?$";
    private static final int NUMERIC_RAW_LENGTH = 4;
    private static final Set<String> OPTION_KEYS =
            Set.of("A", "B", "C", "D", "E", "F");
    private static final Set<String> STATEMENT_KEYS =
            Set.of("A", "B", "C", "D");

    /**
     * Parses the given xlsx stream. The caller owns the InputStream; this
     * method does NOT close it. The internal Workbook is closed automatically
     * via try-with-resources after the {@link ImportResult} is fully built.
     *
     * @throws QuestionException with QUESTION_IMPORT_FILE_INVALID if the stream
     *         is not a readable xlsx workbook, or QUESTION_IMPORT_TEMPLATE_INVALID
     *         if the structure (sheet/header) does not match the template.
     */
    public ImportResult parse(InputStream inputStream) {
        // Only the Workbook is managed here; the InputStream belongs to the caller.
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            String sheetName = workbook.getSheetName(0);
            if (!"Questions".equals(sheetName) || sheet == null) {
                throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_TEMPLATE_INVALID);
            }

            validateHeader(sheet);

            List<ValidQuestionRow> validRows = new ArrayList<>();
            List<RowError> errors = new ArrayList<>();
            // case-insensitive: stores lower-cased question codes
            Set<String> seenCodes = new HashSet<>();
            int totalRows = 0;

            // Data rows start at physical index 1 (row 0 is the header).
            int firstData = sheet.getFirstRowNum() + 1;
            int lastData = sheet.getLastRowNum();
            for (int rowIndex = firstData; rowIndex <= lastData; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                if (isBlankRow(row)) {
                    // Blank rows are skipped and NOT counted as totalRows.
                    continue;
                }
                totalRows++;
                // Workbook row number is 1-based for human-readable reporting.
                int rowNumber = rowIndex + 1;

                // Reject any formula cell in a data field up-front.
                RowError formulaError = findFormulaError(row, rowNumber);
                if (formulaError != null) {
                    errors.add(formulaError);
                    continue;
                }

                List<RowError> rowErrors = new ArrayList<>();
                ValidQuestionRow valid = parseRow(row, rowNumber, seenCodes, rowErrors);
                if (rowErrors.isEmpty() && valid != null) {
                    validRows.add(valid);
                } else {
                    errors.addAll(rowErrors);
                }
            }

            return new ImportResult(totalRows, validRows, errors);
        } catch (QuestionException qe) {
            throw qe;
        } catch (Exception ex) {
            // Corrupt / non-xlsx / unreadable
            throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID);
        }
    }

    private void validateHeader(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_TEMPLATE_INVALID);
        }
        // Must have exactly 23 cells; check the last cell index too.
        short lastCol = header.getLastCellNum();
        if (lastCol != HEADER_COUNT) {
            throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_TEMPLATE_INVALID);
        }
        for (int i = 0; i < HEADER_COUNT; i++) {
            Cell cell = header.getCell(i);
            String value = cell == null ? "" : readHeaderText(cell);
            if (!EXPECTED_HEADERS.get(i).equals(value)) {
                throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_TEMPLATE_INVALID);
            }
        }
    }

    private String readHeaderText(Cell cell) {
        // Header cells may be STRING; do not execute formulas.
        if (cell.getCellType() == CellType.FORMULA) {
            throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_TEMPLATE_INVALID);
        }
        return readStringTrimmed(cell).toLowerCase(Locale.ROOT);
    }

    private boolean isBlankRow(Row row) {
        int last = row.getLastCellNum();
        for (int i = 0; i < last && i < HEADER_COUNT; i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String text = readStringTrimmed(cell);
                if (!text.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Scans all data columns for a FORMULA cell. Returns a RowError naming the
     * first offending field, or null if none. Formulas are never executed and
     * never read via cached results.
     */
    private RowError findFormulaError(Row row, int rowNumber) {
        for (int col = 0; col < HEADER_COUNT; col++) {
            Cell cell = row.getCell(col);
            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                return new RowError(rowNumber, readCodeForError(row),
                        EXPECTED_HEADERS.get(col),
                        QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name(),
                        "Formula cells are not allowed");
            }
        }
        return null;
    }

    private String readCodeForError(Row row) {
        Cell codeCell = row.getCell(COL_QUESTION_CODE);
        return codeCell == null ? null : readStringTrimmed(codeCell);
    }

    private ValidQuestionRow parseRow(Row row, int rowNumber, Set<String> seenCodes,
                                      List<RowError> errors) {
        String code = readStringTrimmed(row.getCell(COL_QUESTION_CODE));
        String typeRaw = readStringTrimmed(row.getCell(COL_QUESTION_TYPE));
        String content = readStringTrimmed(row.getCell(COL_CONTENT));
        String pointsRaw = readStringTrimmed(row.getCell(COL_DEFAULT_POINTS));
        String difficultyRaw = readStringTrimmed(row.getCell(COL_DIFFICULTY));
        String explanation = readStringTrimmed(row.getCell(COL_EXPLANATION));

        if (code.isBlank()) {
            errors.add(rowError(rowNumber, code, "question_code",
                    "question_code is required"));
        }

        // Parse type (defer adding error until we know which validator to run).
        QuestionType type = parseType(typeRaw);

        // Common required-field checks (content).
        if (content.isBlank()) {
            errors.add(rowError(rowNumber, code, "content", "content is required"));
        }

        // Duplicate code detection (case-insensitive) within the file.
        if (!code.isBlank()) {
            String lower = code.toLowerCase(Locale.ROOT);
            if (!seenCodes.add(lower)) {
                errors.add(new RowError(rowNumber, code, "question_code",
                        QuestionErrorCode.QUESTION_IMPORT_DUPLICATE_CODE.name(),
                        "Duplicate question code within the import file"));
            }
        }

        // Parse points/difficulty (shared).
        BigDecimal points = parsePoints(pointsRaw, rowNumber, code, errors);
        QuestionDifficulty difficulty = parseDifficulty(
                difficultyRaw, rowNumber, code, errors);

        ValidQuestionRow valid = null;
        if (type == null) {
            if (!typeRaw.isBlank()) {
                errors.add(new RowError(rowNumber, code, "question_type",
                        QuestionErrorCode.QUESTION_IMPORT_UNSUPPORTED_TYPE.name(),
                        "Unsupported question type: " + typeRaw));
            } else {
                errors.add(rowError(rowNumber, code, "question_type",
                        "question_type is required"));
            }
        } else {
            switch (type) {
                case SINGLE_CHOICE, MULTIPLE_CHOICE -> valid =
                        parseChoiceRow(row, rowNumber, code, type, content, points,
                                difficulty, explanation, errors);
                case TRUE_FALSE_MATRIX -> valid =
                        parseTrueFalseRow(row, rowNumber, code, type, content, points,
                                difficulty, explanation, errors);
                case NUMERIC_FILL -> valid =
                        parseNumericRow(row, rowNumber, code, type, content, points,
                                difficulty, explanation, errors);
            }
        }

        // Only emit a valid row when there were no errors on this row.
        return errors.isEmpty() ? valid : null;
    }

    private QuestionType parseType(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return QuestionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private BigDecimal parsePoints(String raw, int rowNumber, String code,
                                   List<RowError> errors) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(',', '.'));
        } catch (NumberFormatException ex) {
            errors.add(rowError(rowNumber, code, "default_points",
                    "default_points must be a number"));
            return null;
        }
    }

    private QuestionDifficulty parseDifficulty(String raw, int rowNumber, String code,
                                               List<RowError> errors) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return QuestionDifficulty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            errors.add(rowError(rowNumber, code, "difficulty",
                    "difficulty must be EASY, MEDIUM or HARD"));
            return null;
        }
    }

    // ==================== CHOICE rows (SINGLE / MULTIPLE) ====================

    private ValidQuestionRow parseChoiceRow(Row row, int rowNumber, String code,
                                            QuestionType type, String content,
                                            BigDecimal points, QuestionDifficulty difficulty,
                                            String explanation, List<RowError> errors) {
        Map<String, String> options = new LinkedHashMap<>();
        readOption(row, COL_OPTION_A, "A", options);
        readOption(row, COL_OPTION_B, "B", options);
        readOption(row, COL_OPTION_C, "C", options);
        readOption(row, COL_OPTION_D, "D", options);
        readOption(row, COL_OPTION_E, "E", options);
        readOption(row, COL_OPTION_F, "F", options);

        // Required A-D
        for (String k : List.of("A", "B", "C", "D")) {
            if (options.get(k) == null || options.get(k).isBlank()) {
                errors.add(rowError(rowNumber, code, "option_" + k.toLowerCase(Locale.ROOT),
                        "option_" + k.toLowerCase(Locale.ROOT) + " is required"));
            }
        }
        // No gaps: once an option is blank, later ones must be blank.
        validateNoOptionGaps(options, rowNumber, code, errors);

        // Excess fields for this type: statements / numeric / rounding must be blank.
        rejectExcessFields(row, rowNumber, code, errors,
                List.of(COL_STATEMENT_A, COL_STATEMENT_A_ANSWER,
                        COL_STATEMENT_B, COL_STATEMENT_B_ANSWER,
                        COL_STATEMENT_C, COL_STATEMENT_C_ANSWER,
                        COL_STATEMENT_D, COL_STATEMENT_D_ANSWER,
                        COL_NUMERIC_ANSWER, COL_ROUNDING_INSTRUCTION),
                "statement/numeric/rounding fields are not allowed for choice questions");

        // Parse correct answers
        Set<String> correct = parseCorrectAnswers(
                row.getCell(COL_CORRECT_ANSWERS), rowNumber, code, errors);

        // Validate correct answers belong to provided non-blank options
        Set<String> presentKeys = new HashSet<>();
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                presentKeys.add(e.getKey());
            }
        }
        validateCorrectAnswersForChoice(correct, presentKeys, type, rowNumber, code, errors);

        if (!errors.isEmpty()) {
            return null;
        }
        // Drop blank options for a clean map.
        Map<String, String> cleanOptions = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                cleanOptions.put(e.getKey(), e.getValue().trim());
            }
        }
        return new ValidQuestionRow(rowNumber, code, type, content, points, difficulty,
                explanation, cleanOptions, correct, null, null, null, 0, null);
    }

    private void readOption(Row row, int col, String key, Map<String, String> options) {
        Cell cell = row.getCell(col);
        options.put(key, cell == null ? null : readStringTrimmed(cell));
    }

    private void validateNoOptionGaps(Map<String, String> options, int rowNumber,
                                      String code, List<RowError> errors) {
        boolean gap = false;
        for (String k : List.of("A", "B", "C", "D", "E", "F")) {
            String v = options.get(k);
            boolean present = v != null && !v.isBlank();
            if (gap && present) {
                errors.add(new RowError(rowNumber, code, "option_" + k.toLowerCase(Locale.ROOT),
                        QuestionErrorCode.QUESTION_IMPORT_INVALID_OPTIONS.name(),
                        "Options must be contiguous (no gaps)"));
                return;
            }
            if (!present) {
                gap = true;
            }
        }
    }

    private void validateCorrectAnswersForChoice(Set<String> correct, Set<String> presentKeys,
                                                 QuestionType type, int rowNumber, String code,
                                                 List<RowError> errors) {
        if (correct == null) {
            return; // malformed correct_answers already recorded
        }
        // Keys must be valid A-F
        for (String k : correct) {
            if (!k.matches(KEY_PATTERN)) {
                errors.add(new RowError(rowNumber, code, "correct_answers",
                        QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name(),
                        "Invalid correct answer key: " + k));
            }
        }
        // Must belong to provided options
        for (String k : correct) {
            if (k.matches(KEY_PATTERN) && !presentKeys.contains(k)) {
                errors.add(new RowError(rowNumber, code, "correct_answers",
                        QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name(),
                        "Correct answer " + k + " has no matching option"));
            }
        }
        if (type == QuestionType.SINGLE_CHOICE && correct.size() != 1) {
            errors.add(new RowError(rowNumber, code, "correct_answers",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name(),
                    "SINGLE_CHOICE requires exactly one correct answer"));
        }
        if (type == QuestionType.MULTIPLE_CHOICE && correct.size() < 2) {
            errors.add(new RowError(rowNumber, code, "correct_answers",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name(),
                    "MULTIPLE_CHOICE requires at least two correct answers"));
        }
    }

    private Set<String> parseCorrectAnswers(Cell cell, int rowNumber, String code,
                                            List<RowError> errors) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return Set.of();
        }
        String raw = readStringTrimmed(cell);
        if (raw.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (String part : raw.split(",")) {
            String p = part.trim().toUpperCase(Locale.ROOT);
            if (!p.isEmpty()) {
                keys.add(p);
            }
        }
        return keys;
    }

    // ==================== TRUE_FALSE_MATRIX rows ====================

    private ValidQuestionRow parseTrueFalseRow(Row row, int rowNumber, String code,
                                               QuestionType type, String content,
                                               BigDecimal points, QuestionDifficulty difficulty,
                                               String explanation, List<RowError> errors) {
        Map<String, String> statements = new LinkedHashMap<>();
        Map<String, Boolean> answers = new LinkedHashMap<>();
        statements.put("A", readStringTrimmed(row.getCell(COL_STATEMENT_A)));
        statements.put("B", readStringTrimmed(row.getCell(COL_STATEMENT_B)));
        statements.put("C", readStringTrimmed(row.getCell(COL_STATEMENT_C)));
        statements.put("D", readStringTrimmed(row.getCell(COL_STATEMENT_D)));
        answers.put("A", parseBoolean(row.getCell(COL_STATEMENT_A_ANSWER)));
        answers.put("B", parseBoolean(row.getCell(COL_STATEMENT_B_ANSWER)));
        answers.put("C", parseBoolean(row.getCell(COL_STATEMENT_C_ANSWER)));
        answers.put("D", parseBoolean(row.getCell(COL_STATEMENT_D_ANSWER)));

        for (String k : STATEMENT_KEYS) {
            String s = statements.get(k);
            if (s == null || s.isBlank()) {
                errors.add(rowError(rowNumber, code,
                        "statement_" + k.toLowerCase(Locale.ROOT),
                        "statement_" + k.toLowerCase(Locale.ROOT) + " is required"));
            }
            if (answers.get(k) == null) {
                errors.add(rowError(rowNumber, code,
                        "statement_" + k.toLowerCase(Locale.ROOT) + "_answer",
                        "statement_" + k.toLowerCase(Locale.ROOT)
                                + "_answer must be TRUE or FALSE"));
            }
        }
        // Excess fields
        rejectExcessFields(row, rowNumber, code, errors,
                List.of(COL_OPTION_A, COL_OPTION_B, COL_OPTION_C, COL_OPTION_D,
                        COL_OPTION_E, COL_OPTION_F, COL_CORRECT_ANSWERS,
                        COL_NUMERIC_ANSWER, COL_ROUNDING_INSTRUCTION),
                "option/correct_answers/numeric/rounding fields are not allowed for "
                        + "true-false matrix questions");

        if (!errors.isEmpty()) {
            return null;
        }
        // Normalize answers to boolean and trim statements
        Map<String, String> cleanStatements = new LinkedHashMap<>();
        Map<String, Boolean> cleanAnswers = new LinkedHashMap<>();
        for (String k : STATEMENT_KEYS) {
            cleanStatements.put(k, statements.get(k).trim());
            cleanAnswers.put(k, answers.get(k));
        }
        return new ValidQuestionRow(rowNumber, code, type, content, points, difficulty,
                explanation, null, null, cleanStatements, cleanAnswers, null, 0, null);
    }

    private Boolean parseBoolean(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        String raw = readStringTrimmed(cell).toUpperCase(Locale.ROOT);
        if ("TRUE".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("FALSE".equals(raw)) {
            return Boolean.FALSE;
        }
        return null;
    }

    // ==================== NUMERIC_FILL rows ====================

    private ValidQuestionRow parseNumericRow(Row row, int rowNumber, String code,
                                             QuestionType type, String content,
                                             BigDecimal points, QuestionDifficulty difficulty,
                                             String explanation, List<RowError> errors) {
        Cell numericCell = row.getCell(COL_NUMERIC_ANSWER);
        // MUST be a STRING cell — reject NUMERIC and FORMULA.
        if (numericCell == null || numericCell.getCellType() == CellType.BLANK) {
            errors.add(new RowError(rowNumber, code, "numeric_answer",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "numeric_answer is required and must be a text value"));
        } else {
            CellType ct = numericCell.getCellType();
            if (ct == CellType.NUMERIC) {
                errors.add(new RowError(rowNumber, code, "numeric_answer",
                        QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                        "numeric_answer must be a text value, not a number"));
            } else if (ct == CellType.FORMULA) {
                errors.add(new RowError(rowNumber, code, "numeric_answer",
                        QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name(),
                        "numeric_answer must not be a formula"));
            } else if (ct == CellType.STRING) {
                // Raw value — NO trim, NO DataFormatter.
                String raw = numericCell.getStringCellValue();
                validateNumericAnswer(raw, rowNumber, code, errors);
            }
        }

        // rounding_instruction required, non-blank.
        String rounding = readStringTrimmed(row.getCell(COL_ROUNDING_INSTRUCTION));
        if (rounding.isBlank()) {
            errors.add(new RowError(rowNumber, code, "rounding_instruction",
                    QuestionErrorCode.QUESTION_IMPORT_ROUNDING_INSTRUCTION_REQUIRED.name(),
                    "rounding_instruction is required for numeric questions"));
        }

        // Excess fields
        rejectExcessFields(row, rowNumber, code, errors,
                List.of(COL_OPTION_A, COL_OPTION_B, COL_OPTION_C, COL_OPTION_D,
                        COL_OPTION_E, COL_OPTION_F, COL_CORRECT_ANSWERS,
                        COL_STATEMENT_A, COL_STATEMENT_A_ANSWER,
                        COL_STATEMENT_B, COL_STATEMENT_B_ANSWER,
                        COL_STATEMENT_C, COL_STATEMENT_C_ANSWER,
                        COL_STATEMENT_D, COL_STATEMENT_D_ANSWER),
                "option/correct_answers/statement fields are not allowed for numeric questions");

        if (!errors.isEmpty()) {
            return null;
        }
        // Build expected answer from the RAW (untrimmed) string normalized to dot.
        String raw = numericCell.getStringCellValue();
        String normalized = raw.replace(',', '.');
        // Sanity parse (do not use result)
        try {
            new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            // Should have been caught by validateNumericAnswer; defensive.
        }
        return new ValidQuestionRow(rowNumber, code, type, content, points, difficulty,
                explanation, null, null, null, null, normalized, NUMERIC_RAW_LENGTH,
                rounding.trim());
    }

    private void validateNumericAnswer(String raw, int rowNumber, String code,
                                       List<RowError> errors) {
        if (raw == null || raw.length() != NUMERIC_RAW_LENGTH) {
            errors.add(new RowError(rowNumber, code, "numeric_answer",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "numeric_answer must be exactly " + NUMERIC_RAW_LENGTH + " characters"));
            return;
        }
        // Whitespace anywhere is not allowed (no trim was applied).
        if (raw.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == '\r')) {
            errors.add(new RowError(rowNumber, code, "numeric_answer",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "numeric_answer must not contain whitespace"));
            return;
        }
        if (!raw.matches(NUMERIC_PATTERN)) {
            errors.add(new RowError(rowNumber, code, "numeric_answer",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "numeric_answer must match " + NUMERIC_PATTERN));
        }
    }

    // ==================== Shared helpers ====================

    private void rejectExcessFields(Row row, int rowNumber, String code,
                                    List<RowError> errors, List<Integer> cols, String message) {
        for (int col : cols) {
            Cell cell = row.getCell(col);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String v = readStringTrimmed(cell);
                if (!v.isBlank()) {
                    errors.add(new RowError(rowNumber, code, EXPECTED_HEADERS.get(col),
                            QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name(),
                            message));
                }
            }
        }
    }

    private RowError rowError(int rowNumber, String code, String field, String message) {
        return new RowError(rowNumber, code, field,
                QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name(), message);
    }

    /**
     * Reads a cell as a trimmed string. Used for all fields EXCEPT
     * {@code numeric_answer}, which must be read raw without trimming.
     * Does not execute formulas (caller rejects formulas first).
     */
    private String readStringTrimmed(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue() == null ? ""
                    : cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> ""; // caller should have rejected; return blank defensively
            case BLANK, _NONE -> "";
            default -> "";
        };
    }
}
