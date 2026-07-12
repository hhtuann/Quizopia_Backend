package com.quizopia.backend.question.api;

import com.quizopia.backend.authentication.exception.ApiError;
import com.quizopia.backend.question.exception.QuestionErrorCode;
import com.quizopia.backend.question.exception.QuestionImportHttpException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.time.Instant;

/**
 * Scoped advice for {@link QuestionImportController}. Maps import-specific
 * controller-layer exceptions (the {@link QuestionImportHttpException} carrier,
 * plus the Spring multipart framework errors) to the unified {@link ApiError}
 * body with the {@link QuestionErrorCode#QUESTION_IMPORT_FILE_INVALID} code.
 *
 * <p>
 * Scoped (not global) so it never interferes with other controllers. It runs
 * at {@link Ordered#HIGHEST_PRECEDENCE} so the import's tailored statuses (400
 * /
 * 413 / 415) win over the generic global handlers in
 * {@link com.quizopia.backend.security.web.GlobalExceptionHandler}.
 */
@RestControllerAdvice(assignableTypes = QuestionImportController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuestionImportHttpExceptionHandler {

    @ExceptionHandler(QuestionImportHttpException.class)
    public ResponseEntity<ApiError> handleImportHttp(QuestionImportHttpException ex, HttpServletRequest request) {
        return build(ex.getHttpStatus(), ex.getErrorCode(), ex.getMessage(), request);
    }

    /** 413 — request body exceeded the configured multipart max size. */
    @SuppressWarnings("deprecation")
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID,
                "File too large (max 5 MiB)", request);
    }

    /** 400 — required {@code file} part is absent from the multipart request. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex,
            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID,
                "Required file part is missing", request);
    }

    /** 415 — the request Content-Type is not multipart/form-data. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID,
                "Unsupported media type", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, QuestionErrorCode code, String message,
            HttpServletRequest request) {
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), status.value(), code.code(), message, path, MDC.get("traceId"));
        return ResponseEntity.status(status).body(body);
    }
}
