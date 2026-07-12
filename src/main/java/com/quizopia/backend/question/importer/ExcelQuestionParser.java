package com.quizopia.backend.question.importer;

import com.quizopia.backend.question.domain.model.QuestionDifficulty;
import com.quizopia.backend.question.domain.model.QuestionType;
import com.quizopia.backend.question.dto.ImportResult;
import com.quizopia.backend.question.dto.RowError;
import com.quizopia.backend.question.dto.ValidQuestionRow;
import com.quizopia.backend.question.exception.QuestionErrorCode;
import com.quizopia.backend.question.exception.QuestionException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses a Quizopia question-import xlsx workbook into an {@link ImportResult}.
 *
 * <p>Performs <strong>structural and content validation only</strong> — it never
 * touches a repository (the question code is auto-generated + DB duplicate
 * detection is deferred to the service). Row-level errors are collected rather
 * than aborting the workbook; only unrecoverable structural problems (corrupt
 * file, missing sheet, wrong header) throw a {@link QuestionException}.
 *
 * <p><b>Template layout (9 columns, fixed order):</b>
 * {@code question_type, content, difficulty, option_a, option_b, option_c,
 * option_d, correct_answers, explanation}. All four question types share the
 * same {@code option_a..option_d} columns and the single {@code correct_answers}
 * column, whose meaning depends on {@code question_type}:
 * <ul>
 *   <li><b>SINGLE_CHOICE</b> — one letter A-D, e.g. {@code B}.</li>
 *   <li><b>MULTIPLE_CHOICE</b> — the correct letters concatenated without
 *       separators, e.g. {@code ACD}.</li>
 *   <li><b>TRUE_FALSE_MATRIX</b> — exactly 4 characters {@code T}/{@code F} for
 *       statements A-D in order, e.g. {@code TFTF}.</li>
 *   <li><b>NUMERIC_FILL</b> — the numeric answer as text (4 chars), e.g.
 *       {@code 2.50}. Options are left blank.</li>
 * </ul>
 *
 * <p>Strict rules enforced:
 * <ul>
 * <li>Only the {@code "Questions"} sheet (index 0) is read.</li>
 * <li>The header row must match exactly 9 columns in a fixed order.</li>
 * <li>{@code correct_answers} for NUMERIC_FILL must be a STRING cell — never
 *     NUMERIC, never a FORMULA, never trimmed (the template pre-formats the
 *     column as Text).</li>
 * <li>Any FORMULA cell in a data field invalidates its row.</li>
 * </ul>
 */
@Component
public class ExcelQuestionParser {

    /** Exact header order (lower-cased on read for comparison). 9 columns. */
    public static final List<String> EXPECTED_HEADERS = List.of(
            "question_type", "content", "difficulty",
            "option_a", "option_b", "option_c", "option_d",
            "correct_answers", "explanation");

    // Column indexes (0-based).
    private static final int COL_QUESTION_TYPE = 0;
    private static final int COL_CONTENT = 1;
    private static final int COL_DIFFICULTY = 2;
    private static final int COL_OPTION_A = 3;
    private static final int COL_OPTION_B = 4;
    private static final int COL_OPTION_C = 5;
    private static final int COL_OPTION_D = 6;
    private static final int COL_CORRECT_ANSWERS = 7;
    private static final int COL_EXPLANATION = 8;

    private static final int HEADER_COUNT = EXPECTED_HEADERS.size(); // 9

    private static final String KEY_PATTERN = "^[A-D]$";
    private static final String NUMERIC_PATTERN = "^-?[0-9]+([,.][0-9]+)?$";
    private static final int NUMERIC_RAW_LENGTH = 4;
    private static final List<String> OPTION_KEYS = List.of("A", "B", "C", "D");
    /** correct_answers for TRUE_FALSE_MATRIX: exactly 4 chars, each T or F. */
    private static final String TF_PATTERN = "^[TF]{4}$";

    /**
     * Parses the given xlsx stream. The caller owns the InputStream; this method
     * does NOT close it. The internal Workbook is closed automatically via
     * try-with-resources after the {@link ImportResult} is fully built.
     *
     * @throws QuestionException QUESTION_IMPORT_FILE_INVALID if the stream is not
     *                           a readable xlsx workbook, or QUESTION_IMPORT_TEMPLATE_INVALID
     *                           if the structure (sheet/header) does not match the template.
     */
    public ImportResult parse(InputStream inputStream) {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            String sheetName = workbook.getSheetName(0);
            if (!"Questions".equals(sheetName) || sheet == null) {
                throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_TEMPLATE_INVALID);
            }
            validateHeader(sheet);

            List<ValidQuestionRow> validRows = new ArrayList<>();
            List<RowError> errors = new ArrayList<>();
            int totalRows = 0;

            int firstData = sheet.getFirstRowNum() + 1;
            int lastData = sheet.getLastRowNum();
            for (int rowIndex = firstData; rowIndex <= lastData; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row)) {
                    continue;
                }
                totalRows++;
                int rowNumber = rowIndex + 1;

                RowError formulaError = findFormulaError(row, rowNumber);
                if (formulaError != null) {
                    errors.add(formulaError);
                    continue;
                }

                List<RowError> rowErrors = new ArrayList<>();
                ValidQuestionRow valid = parseRow(row, rowNumber, rowErrors);
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
            throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID);
        }
    }

    private void validateHeader(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_TEMPLATE_INVALID);
        }
        if (header.getLastCellNum() != HEADER_COUNT) {
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

    /** Scans all data columns for a FORMULA cell; returns the first offender or null. */
    private RowError findFormulaError(Row row, int rowNumber) {
        for (int col = 0; col < HEADER_COUNT; col++) {
            Cell cell = row.getCell(col);
            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                return new RowError(rowNumber, null, EXPECTED_HEADERS.get(col),
                        QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name(),
                        "Formula cells are not allowed");
            }
        }
        return null;
    }

    private ValidQuestionRow parseRow(Row row, int rowNumber, List<RowError> errors) {
        String typeRaw = readStringTrimmed(row.getCell(COL_QUESTION_TYPE));
        String content = readStringTrimmed(row.getCell(COL_CONTENT));
        String difficultyRaw = readStringTrimmed(row.getCell(COL_DIFFICULTY));
        String explanation = readStringTrimmed(row.getCell(COL_EXPLANATION));

        QuestionType type = parseType(typeRaw);
        QuestionDifficulty difficulty = parseDifficulty(difficultyRaw, rowNumber, errors);

        if (content.isBlank()) {
            errors.add(rowError(rowNumber, "content", "content is required"));
        }
        if (type == null) {
            if (typeRaw.isBlank()) {
                errors.add(rowError(rowNumber, "question_type", "question_type is required"));
            } else {
                errors.add(new RowError(rowNumber, null, "question_type",
                        QuestionErrorCode.QUESTION_IMPORT_UNSUPPORTED_TYPE.name(),
                        "Unsupported question type: " + typeRaw));
            }
            return null;
        }

        Map<String, String> options = new LinkedHashMap<>();
        options.put("A", readStringTrimmed(row.getCell(COL_OPTION_A)));
        options.put("B", readStringTrimmed(row.getCell(COL_OPTION_B)));
        options.put("C", readStringTrimmed(row.getCell(COL_OPTION_C)));
        options.put("D", readStringTrimmed(row.getCell(COL_OPTION_D)));
        String correctRaw = readStringTrimmed(row.getCell(COL_CORRECT_ANSWERS));

        ValidQuestionRow valid = switch (type) {
            case SINGLE_CHOICE, MULTIPLE_CHOICE ->
                    parseChoice(rowNumber, type, content, difficulty, explanation, options, correctRaw, errors);
            case TRUE_FALSE_MATRIX ->
                    parseTrueFalse(rowNumber, type, content, difficulty, explanation, options, correctRaw, errors);
            case NUMERIC_FILL ->
                    parseNumeric(row, rowNumber, type, content, difficulty, explanation, errors);
        };
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

    private QuestionDifficulty parseDifficulty(String raw, int rowNumber, List<RowError> errors) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return QuestionDifficulty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            errors.add(rowError(rowNumber, "difficulty", "difficulty must be EASY, MEDIUM or HARD"));
            return null;
        }
    }

    // ==================== CHOICE rows (SINGLE / MULTIPLE) ====================

    private ValidQuestionRow parseChoice(int rowNumber, QuestionType type, String content,
            QuestionDifficulty difficulty, String explanation,
            Map<String, String> options, String correctRaw, List<RowError> errors) {
        // Options A-D required.
        for (String k : OPTION_KEYS) {
            if (options.get(k) == null || options.get(k).isBlank()) {
                errors.add(rowError(rowNumber, "option_" + k.toLowerCase(Locale.ROOT),
                        "option_" + k.toLowerCase(Locale.ROOT) + " is required"));
            }
        }

        Set<String> correct = parseCorrectKeys(correctRaw, rowNumber, errors);
        Set<String> presentKeys = new HashSet<>();
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                presentKeys.add(e.getKey());
            }
        }
        validateCorrectAnswersForChoice(correct, presentKeys, type, rowNumber, errors);

        if (!errors.isEmpty()) {
            return null;
        }
        Map<String, String> cleanOptions = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                cleanOptions.put(e.getKey(), e.getValue().trim());
            }
        }
        return new ValidQuestionRow(rowNumber, type, content, difficulty, explanation,
                cleanOptions, correct, null, null, null);
    }

    /**
     * Extracts the A-D letters from {@code correct_answers}. The simplified
     * format concatenates them with no separator ({@code "ACD"}), but commas,
     * semicolons and whitespace are tolerated so legacy {@code "A,C,D"} sheets
     * still import. Any other character is an error.
     */
    private Set<String> parseCorrectKeys(String raw, int rowNumber, List<RowError> errors) {
        Set<String> keys = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            return keys;
        }
        String up = raw.toUpperCase(Locale.ROOT);
        for (int i = 0; i < up.length(); i++) {
            char c = up.charAt(i);
            if (c >= 'A' && c <= 'D') {
                keys.add(String.valueOf(c));
            } else if (c == ',' || c == ';' || Character.isWhitespace(c)) {
                // tolerated separator — ignored
            } else {
                errors.add(new RowError(rowNumber, null, "correct_answers",
                        QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name(),
                        "Invalid correct_answers character: " + c));
            }
        }
        return keys;
    }

    private void validateCorrectAnswersForChoice(Set<String> correct, Set<String> presentKeys,
            QuestionType type, int rowNumber, List<RowError> errors) {
        // Keys must be valid A-D
        for (String k : correct) {
            if (!k.matches(KEY_PATTERN)) {
                errors.add(new RowError(rowNumber, null, "correct_answers",
                        QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name(),
                        "Invalid correct answer key: " + k));
            }
        }
        for (String k : correct) {
            if (k.matches(KEY_PATTERN) && !presentKeys.contains(k)) {
                errors.add(new RowError(rowNumber, null, "correct_answers",
                        QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name(),
                        "Correct answer " + k + " has no matching option"));
            }
        }
        if (type == QuestionType.SINGLE_CHOICE && correct.size() != 1) {
            errors.add(new RowError(rowNumber, null, "correct_answers",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name(),
                    "SINGLE_CHOICE requires exactly one correct answer"));
        }
        if (type == QuestionType.MULTIPLE_CHOICE && correct.size() < 2) {
            errors.add(new RowError(rowNumber, null, "correct_answers",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name(),
                    "MULTIPLE_CHOICE requires at least two correct answers"));
        }
    }

    // ==================== TRUE_FALSE_MATRIX rows ====================

    private ValidQuestionRow parseTrueFalse(int rowNumber, QuestionType type, String content,
            QuestionDifficulty difficulty, String explanation,
            Map<String, String> options, String correctRaw, List<RowError> errors) {
        // The 4 options ARE the 4 statements — all required.
        for (String k : OPTION_KEYS) {
            if (options.get(k) == null || options.get(k).isBlank()) {
                errors.add(rowError(rowNumber, "option_" + k.toLowerCase(Locale.ROOT),
                        "option_" + k.toLowerCase(Locale.ROOT) + " is required"));
            }
        }
        // correct_answers = 4-char T/F string for statements A-D in order.
        String tf = correctRaw == null ? "" : correctRaw.toUpperCase(Locale.ROOT);
        Map<String, Boolean> answers = new LinkedHashMap<>();
        if (!tf.matches(TF_PATTERN)) {
            errors.add(new RowError(rowNumber, null, "correct_answers",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name(),
                    "TRUE_FALSE_MATRIX correct_answers must be exactly 4 characters T/F (e.g. TFTF)"));
        } else {
            for (int i = 0; i < OPTION_KEYS.size(); i++) {
                answers.put(OPTION_KEYS.get(i), tf.charAt(i) == 'T');
            }
        }

        if (!errors.isEmpty()) {
            return null;
        }
        Map<String, String> statements = new LinkedHashMap<>();
        for (String k : OPTION_KEYS) {
            statements.put(k, options.get(k).trim());
        }
        return new ValidQuestionRow(rowNumber, type, content, difficulty, explanation,
                null, null, statements, answers, null);
    }

    // ==================== NUMERIC_FILL rows ====================

    @SuppressWarnings("null")
    private ValidQuestionRow parseNumeric(Row row, int rowNumber, QuestionType type, String content,
            QuestionDifficulty difficulty, String explanation, List<RowError> errors) {
        // For NUMERIC_FILL, correct_answers holds the numeric answer as TEXT.
        Cell cell = row.getCell(COL_CORRECT_ANSWERS);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            errors.add(new RowError(rowNumber, null, "correct_answers",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "correct_answers is required and must be a text value"));
        } else {
            CellType ct = cell.getCellType();
            if (ct == CellType.NUMERIC) {
                errors.add(new RowError(rowNumber, null, "correct_answers",
                        QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                        "correct_answers must be a text value, not a number"));
            } else if (ct == CellType.FORMULA) {
                errors.add(new RowError(rowNumber, null, "correct_answers",
                        QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name(),
                        "correct_answers must not be a formula"));
            } else if (ct == CellType.STRING) {
                validateNumericAnswer(cell.getStringCellValue(), rowNumber, errors);
            }
        }

        if (!errors.isEmpty()) {
            return null;
        }
        // Build expected answer from the RAW (untrimmed) string normalized to dot.
        String raw = cell.getStringCellValue();
        String normalized = raw.replace(',', '.');
        return new ValidQuestionRow(rowNumber, type, content, difficulty, explanation,
                null, null, null, null, normalized);
    }

    private void validateNumericAnswer(String raw, int rowNumber, List<RowError> errors) {
        if (raw == null || raw.length() != NUMERIC_RAW_LENGTH) {
            errors.add(new RowError(rowNumber, null, "correct_answers",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "correct_answers for NUMERIC_FILL must be exactly " + NUMERIC_RAW_LENGTH + " characters"));
            return;
        }
        if (raw.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == '\r')) {
            errors.add(new RowError(rowNumber, null, "correct_answers",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "correct_answers for NUMERIC_FILL must not contain whitespace"));
            return;
        }
        if (!raw.matches(NUMERIC_PATTERN)) {
            errors.add(new RowError(rowNumber, null, "correct_answers",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "correct_answers for NUMERIC_FILL must match " + NUMERIC_PATTERN));
        }
    }

    // ==================== Shared helpers ====================

    private RowError rowError(int rowNumber, String field, String message) {
        return new RowError(rowNumber, null, field,
                QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name(), message);
    }

    /**
     * Reads a cell as a trimmed string. Used for all fields EXCEPT the
     * NUMERIC_FILL {@code correct_answers}, which must be read raw without
     * trimming (see {@link #parseNumeric}).
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
