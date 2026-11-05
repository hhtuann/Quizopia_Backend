package com.quizopia.backend.question.domain.markdown;

/** Domain rules for the accepted four-character NUMERIC_FILL answer token. */
public final class QuizNumericAnswer {
    private QuizNumericAnswer() {}

    public static boolean isValid(String token) {
        String normalized = normalize(token);
        if (normalized == null || normalized.length() != 4) {
            return false;
        }

        int minusCount = 0;
        int dotCount = 0;
        int digitCount = 0;
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            if (value >= '0' && value <= '9') {
                digitCount++;
                continue;
            }
            if (value == '-') {
                minusCount++;
                if (index != 0 || minusCount > 1) {
                    return false;
                }
                continue;
            }
            if (value == '.') {
                dotCount++;
                if (index == 0 || index == normalized.length() - 1 || dotCount > 1) {
                    return false;
                }
                continue;
            }
            return false;
        }
        return digitCount > 0;
    }

    public static String normalize(String token) {
        return token == null ? null : token.strip();
    }
}
