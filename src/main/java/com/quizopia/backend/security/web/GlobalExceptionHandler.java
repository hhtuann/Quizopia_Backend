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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.time.Instant;

/**
 * Maps controller-layer exceptions to the unified {@link ApiError} body.
 * Security-filter exceptions (no/invalid token, authorization denial) are
 * rendered by the entry point and access-denied handler respectively; the
 * handlers here cover those rare cases when such exceptions surface from inside
 * a controller method as well.
 *
 * <p>Also stamps {@code Cache-Control: no-store} on EVERY {@link ApiError}
 * body rendered by any controller advice (400/401/403/404/409/429/500 …):
 * error responses describe caller-specific state and must never be cached
 * by any intermediary (the security-filter paths set the same header via
 * {@link ApiErrorWriter#write}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ApiErrorWriter apiErrorWriter;

    public GlobalExceptionHandler(ApiErrorWriter apiErrorWriter) {
        this.apiErrorWriter = apiErrorWriter;
    }

    @Override
    public boolean supports(MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
            MediaType selectedContentType, Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiError) {
            response.getHeaders().setCacheControl(CacheControl.noStore());
        }
        return body;
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


    @ExceptionHandler(com.quizopia.backend.attempt.exception.AttemptException.class)
    public ResponseEntity<ApiError> handleAttemptException(
            com.quizopia.backend.attempt.exception.AttemptException ex, HttpServletRequest request) {
        com.quizopia.backend.attempt.exception.AttemptErrorCode code = ex.getErrorCode();
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(), code.defaultMessage(), path, MDC.get("traceId"));
        return ResponseEntity.status(code.statusCode()).body(body);
    }

    @ExceptionHandler(com.quizopia.backend.community.exception.CommunityException.class)
    public ResponseEntity<ApiError> handleCommunityException(
            com.quizopia.backend.community.exception.CommunityException ex, HttpServletRequest request) {
        com.quizopia.backend.community.exception.CommunityErrorCode code = ex.getErrorCode();
        String path = request != null ? request.getRequestURI() : null;
        ApiError body = new ApiError(
                Instant.now(), code.statusCode(), code.code(), code.defaultMessage(), path, MDC.get("traceId"));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(code.statusCode());
        if (ex.getRetryAfterSeconds() > 0) {
            response = response.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return response.body(body);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
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
        Object route = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        log.error("Unhandled exception for route {}: {}", route == null ? "unmatched" : route,
                ex.getClass().getName());
        ApiError body = apiErrorWriter.build(AuthErrorCode.INTERNAL_ERROR, request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
