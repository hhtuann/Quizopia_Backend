package com.quizopia.backend.grading;

import com.quizopia.backend.authentication.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Maps {@link GradingException} to a sanitized {@link ApiError}. Returns the stable {@link GradingErrorCode}
 * status/code/default-message — NEVER the internal exception message (which could reference grading internals)
 * and NEVER the answer key. Applies globally (grading may be invoked from any attempt/result controller).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GradingExceptionHandler {

    @ExceptionHandler(GradingException.class)
    public ResponseEntity<ApiError> handleGrading(GradingException ex, HttpServletRequest request) {
        GradingErrorCode code = ex.code();
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(Instant.now(), code.statusCode(), code.code(), code.defaultMessage(),
                path, MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.valueOf(code.statusCode())).body(body);
    }
}
