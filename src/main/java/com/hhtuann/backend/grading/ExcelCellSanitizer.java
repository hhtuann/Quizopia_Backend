package com.hhtuann.backend.grading;

/** Neutralizes CSV/Excel formula-injection in user-controlled strings. */
public final class ExcelCellSanitizer {

    private ExcelCellSanitizer() {}

    /** Prefixes an apostrophe if the string starts with =, +, -, or @ (formula-injection vectors). */
    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) return value;
        char c = value.charAt(0);
        if (c == '=' || c == '+' || c == '-' || c == '@') return "'" + value;
        return value;
    }
}
