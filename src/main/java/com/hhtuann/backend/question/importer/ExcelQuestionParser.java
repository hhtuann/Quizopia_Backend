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
 * <p>Performs <strong>structural and content validation only</strong> — it never
 * touches a repository (the question code is auto-generated + DB duplicate
 * detection is deferred to the service). Row-level errors are collected rather
 * than aborting the workbook; only unrecoverable structural problems (corrupt
 * file, missing sheet, wrong header) throw a {@link QuestionException}.
 *
 * <p>Template layout (18 columns, in fixed order): {@code question_type,
 * content, difficulty, option_a..option_d, correct_answers, statement_a..
 * statement_d_answer (8), numeric_answer, explanation}. {@code question_code}
 * (auto-generated), {@code default_points} (always 1) and {@code option_e}/
 * {@code option_f} were removed.
 *
 * <p>Strict rules enforced:
 * <ul>
 * <li>Only the {@code "Questions"} sheet (index 0) is read.</li>
 * <li>The header row must match exactly 18 columns in a fixed order.</li>
 * <li>{@code numeric_answer} must be a STRING cell — never NUMERIC, never a
 * FORMULA, never trimmed, never read through a DataFormatter.</li>
 * <li>Any FORMULA cell in a data field invalidates its row.</li>
 * </ul>
 */
@Component
public class ExcelQuestionParser {

    /** Exact header order (lower-cased on read for comparison). 18 columns. */
    public static final List<String> EXPECTED_HEADERS = List.of(
            "question_type", "content", "difficulty",
            "option_a", "option_b", "option_c", "option_d",
            "correct_answers",
            "statement_a", "statement_a_answer",
            "statement_b", "statement_b_answer",
            "statement_c", "statement_c_answer",
            "statement_d", "statement_d_answer",
            "numeric_answer", "explanation");

    // Column indexes (0-based). question_code / default_points / option_e / option_f
    // were removed: the code is auto-generated, default_points is always 1, only A-D options.
    private static final int COL_QUESTION_TYPE = 0;
    private static final int COL_CONTENT = 1;
    private static final int COL_DIFFICULTY = 2;
    private static final int COL_OPTION_A = 3;
    private static final int COL_OPTION_B = 4;
    private static final int COL_OPTION_C = 5;
    private static final int COL_OPTION_D = 6;
    private static final int COL_CORRECT_ANSWERS = 7;
    private static final int COL_STATEMENT_A = 8;
    private static final int COL_STATEMENT_A_ANSWER = 9;
    private static final int COL_STATEMENT_B = 10;
    private static final int COL_STATEMENT_B_ANSWER = 11;
    private static final int COL_STATEMENT_C = 12;
    private static final int COL_STATEMENT_C_ANSWER = 13;
    private static final int COL_STATEMENT_D = 14;
    private static final int COL_STATEMENT_D_ANSWER = 15;
    private static final int COL_NUMERIC_ANSWER = 16;
    private static final int COL_EXPLANATION = 17;

    private static final int HEADER_COUNT = EXPECTED_HEADERS.size(); // 18

    private static final String KEY_PATTERN = "^[A-D]$";
    private static final String NUMERIC_PATTERN = "^-?[0-9]+([,.][0-9]+)?$";
    private static final int NUMERIC_RAW_LENGTH = 4;
    private static final Set<String> STATEMENT_KEYS = Set.of("A", "B", "C", "D");

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

        if (content.isBlank()) {
            errors.add(rowError(rowNumber, "content", "content is required"));
        }

        QuestionDifficulty difficulty = parseDifficulty(difficultyRaw, rowNumber, errors);

        ValidQuestionRow valid = null;
        if (type == null) {
            if (!typeRaw.isBlank()) {
                errors.add(new RowError(rowNumber, null, "question_type",
                        QuestionErrorCode.QUESTION_IMPORT_UNSUPPORTED_TYPE.name(),
                        "Unsupported question type: " + typeRaw));
            } else {
                errors.add(rowError(rowNumber, "question_type", "question_type is required"));
            }
        } else {
            valid = switch (type) {
                case SINGLE_CHOICE, MULTIPLE_CHOICE ->
                        parseChoiceRow(row, rowNumber, type, content, difficulty, explanation, errors);
                case TRUE_FALSE_MATRIX ->
                        parseTrueFalseRow(row, rowNumber, type, content, difficulty, explanation, errors);
                case NUMERIC_FILL ->
                        parseNumericRow(row, rowNumber, type, content, difficulty, explanation, errors);
            };
        }
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

    private ValidQuestionRow parseChoiceRow(Row row, int rowNumber, QuestionType type,
            String content, QuestionDifficulty difficulty,
            String explanation, List<RowError> errors) {
        Map<String, String> options = new LinkedHashMap<>();
        readOption(row, COL_OPTION_A, "A", options);
        readOption(row, COL_OPTION_B, "B", options);
        readOption(row, COL_OPTION_C, "C", options);
        readOption(row, COL_OPTION_D, "D", options);

        // Required A-D
        for (String k : List.of("A", "B", "C", "D")) {
            if (options.get(k) == null || options.get(k).isBlank()) {
                errors.add(rowError(rowNumber, "option_" + k.toLowerCase(Locale.ROOT),
                        "option_" + k.toLowerCase(Locale.ROOT) + " is required"));
            }
        }

        // Excess fields for this type: statements / numeric must be blank.
        rejectExcessFields(row, rowNumber, errors,
                List.of(COL_STATEMENT_A, COL_STATEMENT_A_ANSWER,
                        COL_STATEMENT_B, COL_STATEMENT_B_ANSWER,
                        COL_STATEMENT_C, COL_STATEMENT_C_ANSWER,
                        COL_STATEMENT_D, COL_STATEMENT_D_ANSWER,
                        COL_NUMERIC_ANSWER),
                "statement/numeric fields are not allowed for choice questions");

        Set<String> correct = parseCorrectAnswers(row.getCell(COL_CORRECT_ANSWERS));
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

    private void readOption(Row row, int col, String key, Map<String, String> options) {
        Cell cell = row.getCell(col);
        options.put(key, cell == null ? null : readStringTrimmed(cell));
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

    private Set<String> parseCorrectAnswers(Cell cell) {
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

    private ValidQuestionRow parseTrueFalseRow(Row row, int rowNumber, QuestionType type,
            String content, QuestionDifficulty difficulty,
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
                errors.add(rowError(rowNumber, "statement_" + k.toLowerCase(Locale.ROOT),
                        "statement_" + k.toLowerCase(Locale.ROOT) + " is required"));
            }
            if (answers.get(k) == null) {
                errors.add(rowError(rowNumber, "statement_" + k.toLowerCase(Locale.ROOT) + "_answer",
                        "statement_" + k.toLowerCase(Locale.ROOT) + "_answer must be TRUE or FALSE"));
            }
        }
        // Excess fields
        rejectExcessFields(row, rowNumber, errors,
                List.of(COL_OPTION_A, COL_OPTION_B, COL_OPTION_C, COL_OPTION_D,
                        COL_CORRECT_ANSWERS, COL_NUMERIC_ANSWER),
                "option/correct_answers/numeric fields are not allowed for true-false matrix questions");

        if (!errors.isEmpty()) {
            return null;
        }
        Map<String, String> cleanStatements = new LinkedHashMap<>();
        Map<String, Boolean> cleanAnswers = new LinkedHashMap<>();
        for (String k : STATEMENT_KEYS) {
            cleanStatements.put(k, statements.get(k).trim());
            cleanAnswers.put(k, answers.get(k));
        }
        return new ValidQuestionRow(rowNumber, type, content, difficulty, explanation,
                null, null, cleanStatements, cleanAnswers, null);
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

    @SuppressWarnings("null")
    private ValidQuestionRow parseNumericRow(Row row, int rowNumber, QuestionType type,
            String content, QuestionDifficulty difficulty,
            String explanation, List<RowError> errors) {
        Cell numericCell = row.getCell(COL_NUMERIC_ANSWER);
        if (numericCell == null || numericCell.getCellType() == CellType.BLANK) {
            errors.add(new RowError(rowNumber, null, "numeric_answer",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "numeric_answer is required and must be a text value"));
        } else {
            CellType ct = numericCell.getCellType();
            if (ct == CellType.NUMERIC) {
                errors.add(new RowError(rowNumber, null, "numeric_answer",
                        QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                        "numeric_answer must be a text value, not a number"));
            } else if (ct == CellType.FORMULA) {
                errors.add(new RowError(rowNumber, null, "numeric_answer",
                        QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name(),
                        "numeric_answer must not be a formula"));
            } else if (ct == CellType.STRING) {
                validateNumericAnswer(numericCell.getStringCellValue(), rowNumber, errors);
            }
        }

        // Excess fields
        rejectExcessFields(row, rowNumber, errors,
                List.of(COL_OPTION_A, COL_OPTION_B, COL_OPTION_C, COL_OPTION_D,
                        COL_CORRECT_ANSWERS,
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
        return new ValidQuestionRow(rowNumber, type, content, difficulty, explanation,
                null, null, null, null, normalized);
    }

    private void validateNumericAnswer(String raw, int rowNumber, List<RowError> errors) {
        if (raw == null || raw.length() != NUMERIC_RAW_LENGTH) {
            errors.add(new RowError(rowNumber, null, "numeric_answer",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "numeric_answer must be exactly " + NUMERIC_RAW_LENGTH + " characters"));
            return;
        }
        if (raw.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == '\r')) {
            errors.add(new RowError(rowNumber, null, "numeric_answer",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "numeric_answer must not contain whitespace"));
            return;
        }
        if (!raw.matches(NUMERIC_PATTERN)) {
            errors.add(new RowError(rowNumber, null, "numeric_answer",
                    QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER.name(),
                    "numeric_answer must match " + NUMERIC_PATTERN));
        }
    }

    // ==================== Shared helpers ====================

    private void rejectExcessFields(Row row, int rowNumber, List<RowError> errors,
            List<Integer> cols, String message) {
        for (int col : cols) {
            Cell cell = row.getCell(col);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String v = readStringTrimmed(cell);
                if (!v.isBlank()) {
                    errors.add(new RowError(rowNumber, null, EXPECTED_HEADERS.get(col),
                            QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name(), message));
                }
            }
        }
    }

    private RowError rowError(int rowNumber, String field, String message) {
        return new RowError(rowNumber, null, field,
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
