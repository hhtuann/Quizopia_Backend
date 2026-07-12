package com.quizopia.backend.question;

import com.quizopia.backend.question.domain.model.QuestionDifficulty;
import com.quizopia.backend.question.domain.model.QuestionType;
import com.quizopia.backend.question.dto.ImportResult;
import com.quizopia.backend.question.dto.RowError;
import com.quizopia.backend.question.dto.ValidQuestionRow;
import com.quizopia.backend.question.exception.QuestionErrorCode;
import com.quizopia.backend.question.exception.QuestionException;
import com.quizopia.backend.question.importer.ExcelQuestionParser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link ExcelQuestionParser} (simplified 9-column format).
 * Workbooks are built in memory with Apache POI; no Spring context is involved.
 * Each assertion checks rowNumber + field + code, not just the error count.
 */
class ExcelQuestionParserTest {

    private final ExcelQuestionParser parser = new ExcelQuestionParser();

    // 0-based column indexes (must match the parser's 9-column header order).
    private static final int C_TYPE = 0;
    private static final int C_CONTENT = 1;
    private static final int C_DIFFICULTY = 2;
    private static final int C_OPT_A = 3;
    private static final int C_OPT_B = 4;
    private static final int C_OPT_C = 5;
    private static final int C_OPT_D = 6;
    private static final int C_CORRECT = 7;
    private static final int C_EXPLANATION = 8;

    // ==================== Happy paths ====================

    @Test
    void parse_validSingleChoice_returnsValidRow() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "What is 2+2?");
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
        assertThat(v.questionType()).isEqualTo(QuestionType.SINGLE_CHOICE);
        assertThat(v.options()).containsEntry("A", "1").containsEntry("D", "4");
        assertThat(v.correctAnswers()).containsExactly("B");
        assertThat(v.difficulty()).isEqualTo(QuestionDifficulty.EASY);
    }

    @Test
    void parse_validMultipleChoiceConcatenated_returnsValidRow() throws Exception {
        // correct_answers is the correct letters concatenated without separators.
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "MULTIPLE_CHOICE");
            set(row, C_CONTENT, "Even numbers");
            set(row, C_DIFFICULTY, "MEDIUM");
            set(row, C_OPT_A, "2");
            set(row, C_OPT_B, "3");
            set(row, C_OPT_C, "4");
            set(row, C_OPT_D, "5");
            set(row, C_CORRECT, "AC");
        }));

        assertThat(r.validRows()).hasSize(1);
        ValidQuestionRow v = r.validRows().get(0);
        assertThat(v.correctAnswers()).containsExactlyInAnyOrder("A", "C");
        assertThat(v.options()).hasSize(4);
    }

    @Test
    void parse_validTrueFalseMatrix_returnsValidRow() throws Exception {
        // The 4 options ARE the 4 statements; correct_answers = T/F for A-D.
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "TRUE_FALSE_MATRIX");
            set(row, C_CONTENT, "Statements");
            set(row, C_DIFFICULTY, "HARD");
            set(row, C_OPT_A, "Sun is a star");
            set(row, C_OPT_B, "Water at 50");
            set(row, C_OPT_C, "Iron heavy");
            set(row, C_OPT_D, "Sound faster");
            set(row, C_CORRECT, "TFTF");
        }));

        assertThat(r.validRows()).hasSize(1);
        ValidQuestionRow v = r.validRows().get(0);
        assertThat(v.statements()).containsEntry("A", "Sun is a star");
        assertThat(v.statementAnswers()).containsEntry("A", true).containsEntry("B", false)
                .containsEntry("C", true).containsEntry("D", false);
    }

    @Test
    void parse_trueFalseLowercaseTf_isAccepted() throws Exception {
        // lowercase "tftf" is upper-cased by the parser.
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "TRUE_FALSE_MATRIX");
            set(row, C_CONTENT, "x");
            set(row, C_OPT_A, "s");
            set(row, C_OPT_B, "s");
            set(row, C_OPT_C, "s");
            set(row, C_OPT_D, "s");
            set(row, C_CORRECT, "tftf");
        }));

        assertThat(r.validRows()).hasSize(1);
        assertThat(r.validRows().get(0).statementAnswers()).containsEntry("A", true)
                .containsEntry("B", false).containsEntry("C", true).containsEntry("D", false);
    }

    @Test
    void parse_validNumericDot_returnsValidRow() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "5/2");
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
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "2,50");
        }));

        assertThat(r.validRows()).hasSize(1);
        assertThat(r.validRows().get(0).expectedAnswer()).isEqualTo("2.50");
    }

    @Test
    void parse_numericLeadingZero02_5_isValid() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "02.5");
        }));

        assertThat(r.validRows()).hasSize(1);
        assertThat(r.validRows().get(0).expectedAnswer()).isEqualTo("02.5");
    }

    @Test
    void parse_negativeNumericValid() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
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
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            row.createCell(C_CORRECT).setCellValue(2.5); // NUMERIC cell
        }));

        assertSingleError(r, "correct_answers", QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericFormula_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            row.createCell(C_CORRECT).setCellFormula("1+1"); // FORMULA
        }));

        // Formula cell is detected up-front as ROW_INVALID.
        assertSingleError(r, "correct_answers", QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID);
    }

    @Test
    void parse_numericThreeChars_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "2.5"); // 3 chars
        }));

        assertSingleError(r, "correct_answers", QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericFiveChars_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "2.500"); // 5 chars
        }));

        assertSingleError(r, "correct_answers", QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericWhitespace_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, " 2.5"); // leading space, 4 chars
        }));

        assertSingleError(r, "correct_answers", QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericExponent_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "1e10"); // 4 chars but invalid
        }));

        assertSingleError(r, "correct_answers", QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    @Test
    void parse_numericPlusSign_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "+1.5"); // 4 chars but plus not allowed
        }));

        assertSingleError(r, "correct_answers", QuestionErrorCode.QUESTION_IMPORT_INVALID_NUMERIC_ANSWER);
    }

    // ==================== CHOICE rejections ====================

    @Test
    void parse_singleChoiceMultipleCorrect_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "AB"); // 2 correct → not single
        }));

        assertSingleError(r, "correct_answers",
                QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS);
    }

    @Test
    void parse_multipleChoiceOneCorrect_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "MULTIPLE_CHOICE");
            set(row, C_CONTENT, "x");
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
    void parse_singleChoiceInvalidCorrectKey_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "E"); // invalid key
        }));

        // An invalid key yields at least one INVALID_CORRECT_ANSWERS error on
        // correct_answers (the empty resulting set also trips the "exactly one"
        // rule, so there can be more than one error).
        assertThat(r.validRows()).isEmpty();
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors()).anyMatch(e -> e.field().equals("correct_answers")
                && e.code().equals(QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS.name()));
    }

    // ==================== TRUE_FALSE rejections ====================

    @Test
    void parse_trueFalseMissingStatement_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "TRUE_FALSE_MATRIX");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "s");
            set(row, C_OPT_B, "s");
            set(row, C_OPT_C, "s");
            // option_d missing (statement D)
            set(row, C_CORRECT, "TFTF");
        }));

        List<RowError> errs = r.errors();
        assertThat(errs).anyMatch(e -> e.field().equals("option_d")
                && e.code().equals(QuestionErrorCode.QUESTION_IMPORT_ROW_INVALID.name()));
    }

    @Test
    void parse_trueFalseInvalidCorrectString_isRejected() throws Exception {
        // correct_answers must be 4 chars T/F; "MAYBE" is invalid.
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "TRUE_FALSE_MATRIX");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "s");
            set(row, C_OPT_B, "s");
            set(row, C_OPT_C, "s");
            set(row, C_OPT_D, "s");
            set(row, C_CORRECT, "MAYBE");
        }));

        assertSingleError(r, "correct_answers",
                QuestionErrorCode.QUESTION_IMPORT_INVALID_CORRECT_ANSWERS);
    }

    // ==================== Type / structural ====================

    @Test
    void parse_unsupportedType_isRejected() throws Exception {
        ImportResult r = parse(workbookWith(row -> {
            set(row, C_TYPE, "ESSAY");
            set(row, C_CONTENT, "x");
            set(row, C_DIFFICULTY, "EASY");
        }));

        assertSingleError(r, "question_type",
                QuestionErrorCode.QUESTION_IMPORT_UNSUPPORTED_TYPE);
    }

    @Test
    void parse_twoRows_bothValid() throws Exception {
        ImportResult r = parse(workbookWith(
                row -> {
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "x");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                },
                row -> {
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "y");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                }));

        assertThat(r.totalRows()).isEqualTo(2);
        assertThat(r.validRows()).hasSize(2);
        assertThat(r.errors()).isEmpty();
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
            // Swap first two headers (question_type <-> content)
            header.createCell(0).setCellValue("content");
            header.createCell(1).setCellValue("question_type");
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
            // One header short of the expected 9.
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
                row -> {
                    /* fully blank row */ },
                row -> {
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "x");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                },
                row -> {
                    /* another blank row */ }));

        // Blank rows not counted; only the one data row counts.
        assertThat(r.totalRows()).isEqualTo(1);
        assertThat(r.validRows()).hasSize(1);
    }

    // ==================== Helpers ====================

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
        RowPopulator[] pops = { row1, row2, row3 };
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

    /** Sets the correct_answers cell as a STRING value (for NUMERIC_FILL tests). */
    private static void setNumericString(Row row, String value) {
        Cell cell = row.createCell(C_CORRECT);
        cell.setCellValue(value); // STRING cell type
    }
}
