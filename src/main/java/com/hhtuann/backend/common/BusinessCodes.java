package com.hhtuann.backend.common;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * Generates opaque business codes for entities whose {@code code} column is an
 * internal owner-scoped unique key that the caller no longer supplies (e.g.
 * question banks, exams, exam sessions). Codes are random {@code [A-Z0-9]}
 * strings, never shown to or chosen by end users.
 *
 * <p>{@link #uniqueCode(int, Predicate)} retries on the (astronomically rare)
 * collision with an existing code, up to a small bound, so the result is
 * guaranteed free of an owner-scoped duplicate.
 */
public final class BusinessCodes {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 8;

    private BusinessCodes() {
    }

    /** A random opaque code of the given length over {@code [A-Z0-9]}. */
    public static String randomCode(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be > 0");
        }
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }

    /**
     * A random code that does NOT already exist, per {@code exists}. Retries a
     * bounded number of times; at 20 chars (~103 bits of entropy) a collision
     * is effectively impossible, so exhaustion signals a generator/seed fault.
     *
     * @param exists must return true when the candidate already belongs to the
     *              relevant owner scope (e.g.
     *              {@code repo.existsByOwnerTeacherIdAndCodeIgnoreCase(ownerId, c)})
     */
    public static String uniqueCode(int length, Predicate<String> exists) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String candidate = randomCode(length);
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique business code after " + MAX_ATTEMPTS + " attempts");
    }
}
