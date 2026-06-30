package com.hhtuann.backend.question.api;

import com.hhtuann.backend.authentication.exception.ApiError;
import com.hhtuann.backend.question.exception.QuestionErrorCode;
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
 * Handles Bean Validation errors for {@link QuestionBankController} by returning
 * {@link QuestionErrorCode#QUESTION_VALIDATION_ERROR} (400) instead of the
 * global {@code AUTH_VALIDATION_ERROR}. Scoped to the question controller only;
 * all other controllers keep the Day 4 auth validation behavior.
 */
@RestControllerAdvice(assignableTypes = QuestionBankController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuestionValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        QuestionErrorCode code = QuestionErrorCode.QUESTION_VALIDATION_ERROR;
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(),
                code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
