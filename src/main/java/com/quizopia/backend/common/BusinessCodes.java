package com.quizopia.backend.common;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * Generates human-readable business codes for entities whose {@code code} column
 * is an internal unique key that the caller no longer supplies (question banks,
 * exams, exam sessions, questions). Codes use a short uppercase PREFIX followed
 * by zero-padded random digits — e.g. {@code QU12345678}, {@code EX00001234}.
 *
 * <p>Prefix convention:
 * <ul>
 *   <li>{@code QB} — question banks</li>
 *   <li>{@code QU} — questions (Excel import)</li>
 *   <li>{@code EX} — exams</li>
 *   <li>{@code ES} — exam sessions</li>
 * </ul>
 */
public final class BusinessCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 8;

    private BusinessCodes() {
    }

    /**
     * A human-readable code: {@code prefix + zero-padded random digits}.
     * Retries on collision up to {@value #MAX_ATTEMPTS} times.
     *
     * @param prefix short uppercase prefix (e.g. "QU", "EX")
     * @param digits number of numeric digits (8 → ~100M combinations)
     * @param exists returns true when the candidate already exists in the relevant scope
     * @return a unique code like "QU12345678"
     */
    public static String readableCode(String prefix, int digits, Predicate<String> exists) {
        int max = (int) Math.pow(10, digits);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            int num = RANDOM.nextInt(max);
            String code = prefix + String.format("%0" + digits + "d", num);
            if (!exists.test(code)) {
                return code;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique code with prefix '" + prefix + "' after " + MAX_ATTEMPTS + " attempts");
    }
}
