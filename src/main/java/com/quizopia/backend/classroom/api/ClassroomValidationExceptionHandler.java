package com.quizopia.backend.classroom.api;

import com.quizopia.backend.authentication.exception.ApiError;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
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
 * Scoped validation handler for classroom controllers — returns
 * CLASSROOM_VALIDATION_ERROR (400) instead of the global AUTH_VALIDATION_ERROR.
 */
@RestControllerAdvice(basePackages = "com.quizopia.backend.classroom.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClassroomValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        ClassroomErrorCode code = ClassroomErrorCode.CLASSROOM_VALIDATION_ERROR;
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(),
                code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
