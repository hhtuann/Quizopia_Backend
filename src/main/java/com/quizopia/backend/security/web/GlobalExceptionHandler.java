package com.quizopia.backend.security.web;

import com.quizopia.backend.authentication.exception.ApiError;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.question.exception.QuestionErrorCode;
import com.quizopia.backend.question.exception.QuestionException;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.academic.exception.AcademicErrorCode;
import com.quizopia.backend.academic.exception.AcademicException;
import com.quizopia.backend.user.exception.UserErrorCode;
import com.quizopia.backend.user.exception.UserException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps controller-layer exceptions to the unified {@link ApiError} body.
 * Security-filter exceptions (no/invalid token, authorization denial) are
 * rendered by the entry point and access-denied handler respectively; the
 * handlers here cover those rare cases when such exceptions surface from inside
 * a controller method as well.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ApiErrorWriter apiErrorWriter;

    public GlobalExceptionHandler(ApiErrorWriter apiErrorWriter) {
        this.apiErrorWriter = apiErrorWriter;
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        ApiError body = apiErrorWriter.build(ex.getErrorCode(), request);
        return ResponseEntity.status(ex.getErrorCode().statusCode()).body(body);
    }

    @ExceptionHandler(QuestionException.class)
    public ResponseEntity<ApiError> handleQuestionException(QuestionException ex, HttpServletRequest request) {
        QuestionErrorCode code = ex.getErrorCode();
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(), code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(code.statusCode()).body(body);
    }

    @ExceptionHandler(ExamException.class)
    public ResponseEntity<ApiError> handleExamException(ExamException ex, HttpServletRequest request) {
        ExamErrorCode code = ex.getErrorCode();
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(), code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(code.statusCode()).body(body);
    }

    @ExceptionHandler(ClassroomException.class)
    public ResponseEntity<ApiError> handleClassroomException(ClassroomException ex, HttpServletRequest request) {
        ClassroomErrorCode code = ex.getErrorCode();
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(), code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(code.statusCode()).body(body);
    }

    @ExceptionHandler(AcademicException.class)
    public ResponseEntity<ApiError> handleAcademicException(AcademicException ex, HttpServletRequest request) {
        AcademicErrorCode code = ex.getErrorCode();
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(), code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(code.statusCode()).body(body);
    }

    @ExceptionHandler(UserException.class)
    public ResponseEntity<ApiError> handleUserException(UserException ex, HttpServletRequest request) {
        UserErrorCode code = ex.getErrorCode();
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(), code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(code.statusCode()).body(body);
    }

    @ExceptionHandler(com.quizopia.backend.attempt.exception.AttemptException.class)
    public ResponseEntity<ApiError> handleAttemptException(
            com.quizopia.backend.attempt.exception.AttemptException ex, HttpServletRequest request) {
        com.quizopia.backend.attempt.exception.AttemptErrorCode code = ex.getErrorCode();
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(), code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(code.statusCode()).body(body);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiError> handleValidation(Exception ex, HttpServletRequest request) {
        ApiError body = apiErrorWriter.build(AuthErrorCode.AUTH_VALIDATION_ERROR, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiError> handleSpringAuthentication(
            org.springframework.security.core.AuthenticationException ex, HttpServletRequest request) {
        ApiError body = apiErrorWriter.build(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID, request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ApiError body = apiErrorWriter.build(AuthErrorCode.AUTH_ACCESS_DENIED, request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Log class + message only (our exceptions never embed secrets); never
        // leak the stack trace or the raw message to the client.
        log.error("Unhandled exception for request to {}: {}", request.getRequestURI(), ex.toString());
        ApiError body = apiErrorWriter.build(AuthErrorCode.INTERNAL_ERROR, request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
