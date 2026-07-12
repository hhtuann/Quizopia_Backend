package com.quizopia.backend.attempt.api;

import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.authentication.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

/**
 * Scoped validation advice for the attempt API package. Intercepts binding/validation
 * failures (malformed JSON, invalid UUID, @Valid failures) within attempt controllers
 * and returns {@code 400 ATTEMPT_VALIDATION_ERROR} instead of the global
 * {@code AUTH_VALIDATION_ERROR}. Does NOT change global semantics for auth/question/exam.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.quizopia.backend.attempt.api")
public class AttemptValidationExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleValidation(Exception ex, HttpServletRequest request) {
        AttemptErrorCode code = AttemptErrorCode.ATTEMPT_VALIDATION_ERROR;
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(), code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
