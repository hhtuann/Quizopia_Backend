package com.quizopia.backend.exam.api;

import com.quizopia.backend.authentication.exception.ApiError;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Scoped validation handler for exam controllers — returns EXAM_VALIDATION_ERROR
 * (400) instead of the global AUTH_VALIDATION_ERROR.
 */
@RestControllerAdvice(basePackages = "com.quizopia.backend.exam.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExamValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        ExamErrorCode code = ExamErrorCode.EXAM_VALIDATION_ERROR;
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(),
                code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
