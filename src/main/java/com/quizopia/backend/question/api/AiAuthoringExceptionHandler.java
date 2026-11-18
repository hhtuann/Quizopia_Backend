package com.quizopia.backend.question.api;

import com.quizopia.backend.authentication.exception.ApiError;
import com.quizopia.backend.question.exception.AiAuthoringException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;

/**
 * Scoped advice for {@link AiAuthoringController}, mirroring
 * {@link QuestionImportHttpExceptionHandler}. Maps AI-authoring errors to the
 * unified {@link ApiError} body. Messages are always server-authored: no
 * upstream provider body, API key or document text is ever included.
 */
@RestControllerAdvice(assignableTypes = AiAuthoringController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiAuthoringExceptionHandler {

    @ExceptionHandler(AiAuthoringException.class)
    public ResponseEntity<ApiError> handleAiAuthoring(AiAuthoringException ex, HttpServletRequest request) {
        return build(ex.getHttpStatus(), ex.getErrorCode().code(), ex.getMessage(), request);
    }

    /** 413 — request body exceeded the configured multipart max size. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException ex,
            HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "AI_DOCUMENT_TOO_LARGE",
                "File too large (max 5 MiB)", request);
    }

    /** 400 — required {@code file} part is absent from the multipart request. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex,
            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "AI_DOCUMENT_INVALID",
                "Required file part is missing", request);
    }

    /** 415 — the request Content-Type is not multipart/form-data. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "AI_DOCUMENT_UNSUPPORTED_TYPE",
                "Unsupported media type", request);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
            HttpServletRequest request) {
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), status.value(), code, message, path, MDC.get("traceId"));
        return ResponseEntity.status(status).body(body);
    }
}
