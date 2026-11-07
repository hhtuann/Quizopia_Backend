package com.quizopia.backend.community.exception;

/**
 * Community module exception → {@code GlobalExceptionHandler} → ApiError.
 * Carries an optional {@code retryAfterSeconds} (rate limiting, §9) that
 * the handler renders as a {@code Retry-After} response header.
 */
public class CommunityException extends RuntimeException {

    private final CommunityErrorCode errorCode;
    private final long retryAfterSeconds;

    public CommunityException(CommunityErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), 0);
    }

    public CommunityException(CommunityErrorCode errorCode, String message) {
        this(errorCode, message, 0);
    }

    public CommunityException(CommunityErrorCode errorCode, long retryAfterSeconds) {
        this(errorCode, errorCode.defaultMessage(), retryAfterSeconds);
    }

    private CommunityException(CommunityErrorCode errorCode, String message, long retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public CommunityErrorCode getErrorCode() {
        return errorCode;
    }

    /** &gt; 0 only for COMMUNITY_RATE_LIMITED. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
