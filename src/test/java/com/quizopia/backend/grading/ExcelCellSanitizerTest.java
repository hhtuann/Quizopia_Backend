package com.quizopia.backend.grading;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ExcelCellSanitizer} — formula-injection protection. */
class ExcelCellSanitizerTest {

    @Test void normalStringUnchanged() {
        assertThat(ExcelCellSanitizer.sanitize("Alice")).isEqualTo("Alice");
    }

    @Test void startsWithEqualsIsNeutralized() {
        assertThat(ExcelCellSanitizer.sanitize("=cmd")).isEqualTo("'=cmd");
    }

    @Test void startsWithPlusIsNeutralized() {
        assertThat(ExcelCellSanitizer.sanitize("+1+1")).isEqualTo("'+1+1");
    }

    @Test void startsWithMinusIsNeutralized() {
        assertThat(ExcelCellSanitizer.sanitize("-2+3")).isEqualTo("'-2+3");
    }

    @Test void startsWithAtIsNeutralized() {
        assertThat(ExcelCellSanitizer.sanitize("@admin")).isEqualTo("'@admin");
    }

    @Test void nullReturnsNull() { assertThat(ExcelCellSanitizer.sanitize(null)).isNull(); }
    @Test void emptyReturnsEmpty() { assertThat(ExcelCellSanitizer.sanitize("")).isEmpty(); }
    @Test void midStringEqualsUnchanged() { assertThat(ExcelCellSanitizer.sanitize("a=b")).isEqualTo("a=b"); }
}
