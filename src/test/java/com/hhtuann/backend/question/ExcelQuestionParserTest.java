package com.hhtuann.backend.question;

import com.hhtuann.backend.question.domain.model.QuestionDifficulty;
import com.hhtuann.backend.question.domain.model.QuestionType;
import com.hhtuann.backend.question.dto.ImportResult;
import com.hhtuann.backend.question.dto.RowError;
import com.hhtuann.backend.question.dto.ValidQuestionRow;
import com.hhtuann.backend.question.exception.QuestionErrorCode;
import com.hhtuann.backend.question.exception.QuestionException;
import com.hhtuann.backend.question.importer.ExcelQuestionParser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link ExcelQuestionParser}. Workbooks are built in
 * memory with Apache POI; no Spring context is involved. Each assertion
 * checks rowNumber + field + code, not just the error count.
 */
class ExcelQuestionParserTest {

    private final ExcelQuestionParser parser = new ExcelQuestionParser();

    // 0-based column indexes (must match the parser's header order).
    private static final int C_CODE = 0;
    private static final int C_TYPE = 1;
    private static final int C_CONTENT = 2;
    private static final int C_POINTS = 3;
    private static final int C_DIFFICULTY = 4;
    private static final int C_OPT_A = 5;
    private static final int C_OPT_B = 6;
    private static final int C_OPT_C = 7;
    private static final int C_OPT_D = 8;
    private static final int C_OPT_E = 9;
    private static final int C_OPT_F = 10;
    private static final int C_CORRECT = 11;
    private static final int C_ST_A = 12;
    private static final int C_ST_A_ANS = 13;
    private static final int C_ST_B = 14;
    private static final int C_ST_B_ANS = 15;
    private static final int C_ST_C = 16;
    private static final int C_ST_C_ANS = 17;
    private static final int C_ST_D = 18;
    private static final int C_ST_D_ANS = 19;
    private static final int C_NUMERIC = 20;
    private static final int C_EXPL = 21;

    // ==================== Happy paths ====================

    @Test
    void parse_validSingleChoice_returnsValidRow() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "Q1");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "What is 2+2?");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "1");
            set(row, C_OPT_B, "2");
            set(row, C_OPT_C, "3");
            set(row, C_OPT_D, "4");
            set(row, C_CORRECT, "B");
        }));

        assertThat(r.totalRows()).isEqualTo(1);
        assertThat(r.errors()).isEmpty();
        assertThat(r.validRows()).hasSize(1);
        ValidQuestionRow v = r.validRows().get(0);
        assertThat(v.rowNumber()).isEqualTo(2); // data starts at sheet row 1 → display 2
        assertThat(v.questionCode()).isEqualTo("Q1");
        assertThat(v.questionType()).isEqualTo(QuestionType.SINGLE_CHOICE);
        assertThat(v.options()).containsEntry("A", "1").containsEntry("D", "4");
        assertThat(v.correctAnswers()).containsExactly("B");
        assertThat(v.defaultPoints()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(v.difficulty()).isEqualTo(QuestionDifficulty.EASY);
    }

    @Test
    void parse_validMultipleChoice_returnsValidRow() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "Q2");
            set(row, C_TYPE, "MULTIPLE_CHOICE");
            set(row, C_CONTENT, "Even numbers");
            set(row, C_POINTS, "2");
            set(row, C_DIFFICULTY, "MEDIUM");
            set(row, C_OPT_A, "2");
            set(row, C_OPT_B, "3");
            set(row, C_OPT_C, "4");
            set(row, C_OPT_D, "5");
            set(row, C_CORRECT, "A,C");
        }));

        assertThat(r.validRows()).hasSize(1);
        ValidQuestionRow v = r.validRows().get(0);
        assertThat(v.correctAnswers()).containsExactlyInAnyOrder("A", "C");
        assertThat(v.options()).hasSize(4);
    }

    @Test
    void parse_validTrueFalseMatrix_returnsValidRow() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "Q3");
            set(row, C_TYPE, "TRUE_FALSE_MATRIX");
            set(row, C_CONTENT, "Statements");
            set(row, C_POINTS, "3");
            set(row, C_DIFFICULTY, "HARD");
            set(row, C_ST_A, "Sun is a star");
            set(row, C_ST_A_ANS, "TRUE");
            set(row, C_ST_B, "Water at 50");
            set(row, C_ST_B_ANS, "FALSE");
            set(row, C_ST_C, "Iron heavy");
            set(row, C_ST_C_ANS, "true"); // case-insensitive
            set(row, C_ST_D, "Sound faster");
            set(row, C_ST_D_ANS, "false");
        }));

        assertThat(r.validRows()).hasSize(1);
        ValidQuestionRow v = r.validRows().get(0);
        assertThat(v.statements()).containsEntry("A", "Sun is a star");
        assertThat(v.statementAnswers()).containsEntry("A", true).containsEntry("B", false)
                .containsEntry("C", true).containsEntry("D", false);
    }

    @Test
    void parse_validNumericDot_returnsValidRow() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "Q4");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "5/2");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "2.50");
        }));

        assertThat(r.validRows()).hasSize(1);
        ValidQuestionRow v = r.validRows().get(0);
        assertThat(v.expectedAnswer()).isEqualTo("2.50");
    }

    @Test
    void parse_numericComma_normalizesToDotAndKeepsTrailingZero() throws Exception {
        // "2,50" → normalized "2.50" (4 chars, trailing zero preserved)
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "Q5");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "2,50");
        }));

        assertThat(r.validRows()).hasSize(1);
        assertThat(r.validRows().get(0).expectedAnswer()).isEqualTo("2.50");
    }

    @Test
    void parse_numericLeadingZero02_5_isValid() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "Q6");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "02.5");
        }));

        assertThat(r.validRows()).hasSize(1);
        assertThat(r.validRows().get(0).expectedAnswer()).isEqualTo("02.5");
    }

    @Test
    void parse_negativeNumericValid() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "Q7");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "-1.5");
        }));

        assertThat(r.validRows()).hasSize(1);
        assertThat(r.validRows().get(0).expectedAnswer()).isEqualTo("-1.5");
    }

    // ==================== NUMERIC rejections ====================

    @Test
    void parse_numericCellTypeNumeric_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QN1");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            row.createCell(C_NUMERIC).setCellValue(2.5); // NUMERIC cell
        }));

        assertSingleError(r, C_NUMERICLabel(), QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericFormula_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QN2");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            row.createCell(C_NUMERIC).setCellFormula("1+1"); // FORMULA
        }));

        // Formula cell is detected up-front as ROW_INVALID.
        assertSingleError(r, C_NUMERICLabel(), QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID);
    }

    @Test
    void parse_numericThreeChars_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QN3");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "2.5"); // 3 chars
        }));

        assertSingleError(r, C_NUMERICLabel(), QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericFiveChars_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QN4");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "2.500"); // 5 chars
        }));

        assertSingleError(r, C_NUMERICLabel(), QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericWhitespace_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QN5");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, " 2.5"); // leading space, 4 chars
        }));

        assertSingleError(r, C_NUMERICLabel(), QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericExponent_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QN6");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "1e10"); // 4 chars but invalid
        }));

        assertSingleError(r, C_NUMERICLabel(), QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericPlusSign_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QN7");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "+1.5"); // 4 chars but plus not allowed
        }));

        assertSingleError(r, C_NUMERICLabel(), QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericNoRoundingColumn_stillValid() throws Exception {
        // roundingInstruction now lives in the content; the rounding column is gone entirely.
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QN8");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "2.50");
        }));

        assertThat(r.validRows()).hasSize(1);
        assertThat(r.errors()).isEmpty();
    }

    // ==================== CHOICE rejections ====================

    @Test
    void parse_singleChoiceMultipleCorrect_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QS1");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A,B");
        }));

        assertSingleError(r, "correct_answers",
                QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS);
    }

    @Test
    void parse_multipleChoiceOneCorrect_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QM1");
            set(row, C_TYPE, "MULTIPLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A"); // only 1
        }));

        assertSingleError(r, "correct_answers",
                QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS);
    }

    @Test
    void parse_choiceOptionGap_isRejected() throws Exception {
        // E blank, F filled → gap
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QG1");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_OPT_F, "f"); // E missing → gap
            set(row, C_CORRECT, "A");
        }));

        assertSingleError(r, "option_f", QuestionErrorCode.QUESTION_IMPORT_INVALID_OPTIONS);
    }

    @Test
    void parse_singleChoiceExcessStatement_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QE1");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
            set(row, C_ST_A, "excess"); // not allowed for choice
        }));

        assertSingleError(r, "statement_a", QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID);
    }

    // ==================== TRUE_FALSE rejections ====================

    @Test
    void parse_trueFalseMissingStatement_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QT1");
            set(row, C_TYPE, "TRUE_FALSE_MATRIX");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_ST_A, "s");
            set(row, C_ST_A_ANS, "TRUE");
            set(row, C_ST_B, "s");
            set(row, C_ST_B_ANS, "FALSE");
            set(row, C_ST_C, "s");
            set(row, C_ST_C_ANS, "TRUE");
            // statement_d missing
            set(row, C_ST_D_ANS, "FALSE");
        }));

        List<RowError> errs = r.errors();
        assertThat(errs).anyMatch(e -> e.field().equals("statement_d")
                && e.code().equals(QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name()));
    }

    @Test
    void parse_trueFalseMissingBoolean_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QT2");
            set(row, C_TYPE, "TRUE_FALSE_MATRIX");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_ST_A, "s");
            set(row, C_ST_A_ANS, "MAYBE"); // invalid
            set(row, C_ST_B, "s");
            set(row, C_ST_B_ANS, "FALSE");
            set(row, C_ST_C, "s");
            set(row, C_ST_C_ANS, "TRUE");
            set(row, C_ST_D, "s");
            set(row, C_ST_D_ANS, "FALSE");
        }));

        List<RowError> errs = r.errors();
        assertThat(errs).anyMatch(e -> e.field().equals("statement_a_answer")
                && e.code().equals(QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name()));
    }

    // ==================== Type / duplicate / structural ====================

    @Test
    void parse_unsupportedType_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_CODE, "QU1");
            set(row, C_TYPE, "ESSAY");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
        }));

        assertSingleError(r, "question_type",
                QuestionErrorCode.QUESTION_IMPORT_UNSUPPORTED_TYPE);
    }

    @Test
    void parse_duplicateCodeDifferentCase_secondRejected() throws Exception {
        ImportResult r = parse(workbookWith(
                row -> {
                    set(row, C_CODE, "DUP-1");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "x");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                },
                row -> {
                    set(row, C_CODE, "dup-1"); // same code, different case
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "y");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                }));

        assertThat(r.totalRows()).isEqualTo(2);
        assertThat(r.validRows()).hasSize(1);
        assertThat(r.validRows().get(0).questionCode()).isEqualTo("DUP-1");
        assertThat(r.errors()).hasSize(1);
        RowError err = r.errors().get(0);
        assertThat(err.rowNumber()).isEqualTo(3); // second data row
        assertThat(err.field()).isEqualTo("question_code");
        assertThat(err.code()).isEqualTo(QuestionErrorCode.QUESTION_IMPORT_DUPLICATE_CODE.name());
    }

    @Test
    void parse_missingQuestionsSheet_throwsTemplateInvalid() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("NotQuestions");
            assertThatThrownBy(() -> parseStream(wb))
                    .isInstanceOf(QuestionException.class)
                    .extracting(e -> ((QuestionException) e).getErrorCode())
                    .isEqualTo(QuestionErrorCode.QUESTION_IMPORT_TEMPLATE_INVALID);
        }
    }

    @Test
    void parse_headerWrongOrder_throwsTemplateInvalid() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Questions");
            Row header = sheet.createRow(0);
            // Swap first two headers
            header.createCell(0).setCellValue("question_type");
            header.createCell(1).setCellValue("question_code");
            for (int i = 2; i < ExcelQuestionParser.EXPECTED_HEADERS.size(); i++) {
                header.createCell(i).setCellValue(ExcelQuestionParser.EXPECTED_HEADERS.get(i));
            }
            assertThatThrownBy(() -> parseStream(wb))
                    .isInstanceOf(QuestionException.class)
                    .extracting(e -> ((QuestionException) e).getErrorCode())
                    .isEqualTo(QuestionErrorCode.QUESTION_IMPORT_TEMPLATE_INVALID);
        }
    }

    @Test
    void parse_headerMissingColumn_throwsTemplateInvalid() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Questions");
            Row header = sheet.createRow(0);
            // Only 22 of 23 headers
            for (int i = 0; i < ExcelQuestionParser.EXPECTED_HEADERS.size() - 1; i++) {
                header.createCell(i).setCellValue(ExcelQuestionParser.EXPECTED_HEADERS.get(i));
            }
            assertThatThrownBy(() -> parseStream(wb))
                    .isInstanceOf(QuestionException.class)
                    .extracting(e -> ((QuestionException) e).getErrorCode())
                    .isEqualTo(QuestionErrorCode.QUESTION_IMPORT_TEMPLATE_INVALID);
        }
    }

    @Test
    void parse_corruptNonXlsxStream_throwsFileInvalid() {
        byte[] notXlsx = "this is definitely not an xlsx file".getBytes();
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(notXlsx)))
                .isInstanceOf(QuestionException.class)
                .extracting(e -> ((QuestionException) e).getErrorCode())
                .isEqualTo(QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID);
    }

    @Test
    void parse_blankRowsAreIgnored() throws Exception {
        ImportResult r = parse(workbookWith(
                row -> { /* fully blank row */ },
                row -> {
                    set(row, C_CODE, "QB1");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "x");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                },
                row -> { /* another blank row */ }));

        // Blank rows not counted; only the one data row counts.
        assertThat(r.totalRows()).isEqualTo(1);
        assertThat(r.validRows()).hasSize(1);
    }

    // ==================== Helpers ====================

    private static String C_NUMERICLabel() {
        return "numeric_answer";
    }

    private void assertSingleError(ImportResult r, String field, QuestionErrorCode code) {
        assertThat(r.validRows()).isEmpty();
        assertThat(r.errors()).hasSize(1);
        RowError err = r.errors().get(0);
        assertThat(err.rowNumber()).isEqualTo(2);
        assertThat(err.field()).isEqualTo(field);
        assertThat(err.code()).isEqualTo(code.name());
    }

    @FunctionalInterface
    private interface RowPopulator {
        void populate(Row row);
    }

    /** Builds a workbook with a Questions sheet + header, then one data row. */
    private Workbook workbookWith(RowPopulator populator) throws IOException {
        return workbookWith(populator, null, null);
    }

    /** Builds a workbook with a Questions sheet + header, then up to 2 rows. */
    private Workbook workbookWith(RowPopulator row1, RowPopulator row2) throws IOException {
        return workbookWith(row1, row2, null);
    }

    /** Builds a workbook with a Questions sheet + header, then up to 3 rows. */
    private Workbook workbookWith(RowPopulator row1, RowPopulator row2, RowPopulator row3)
            throws IOException {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Questions");
        Row header = sheet.createRow(0);
        for (int i = 0; i < ExcelQuestionParser.EXPECTED_HEADERS.size(); i++) {
            header.createCell(i).setCellValue(ExcelQuestionParser.EXPECTED_HEADERS.get(i));
        }
        RowPopulator[] pops = {row1, row2, row3};
        for (int i = 0; i < pops.length; i++) {
            if (pops[i] != null) {
                pops[i].populate(sheet.createRow(i + 1));
            }
        }
        return wb;
    }

    private ImportResult parse(Workbook wb) throws IOException {
        return parser.parse(new ByteArrayInputStream(toBytes(wb)));
    }

    private ImportResult parseStream(Workbook wb) throws IOException {
        return parser.parse(new ByteArrayInputStream(toBytes(wb)));
    }

    private byte[] toBytes(Workbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    private static void set(Row row, int col, String value) {
        if (value == null) {
            return;
        }
        row.createCell(col).setCellValue(value);
    }

    /** Sets the numeric_answer cell as a STRING (Text-formatted) value. */
    @SuppressWarnings("unused")
    private static void setNumericString(Row row, String value) {
        Cell cell = row.createCell(C_NUMERIC);
        cell.setCellValue(value); // STRING cell type
    }

    /** Unused but reserved for future text-format assertions. */
    @SuppressWarnings("unused")
    private static CellStyle textFormat(Workbook wb) {
        DataFormat df = wb.createDataFormat();
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(df.getFormat("@"));
        return s;
    }
}
