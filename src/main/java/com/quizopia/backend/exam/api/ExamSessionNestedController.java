package com.quizopia.backend.exam.api;

import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.exam.dto.CreateExamSessionRequest;
import com.quizopia.backend.exam.dto.ExamSessionDetailResponse;
import com.quizopia.backend.exam.dto.ExamSessionListItem;
import com.quizopia.backend.exam.dto.SessionClassesResponse;
import com.quizopia.backend.exam.dto.SessionRosterItem;
import com.quizopia.backend.exam.dto.UpdateExamSessionRequest;
import com.quizopia.backend.question.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Nested session routes — the canonical API surface (issue #2 §4):
 * {@code /api/exams/{examId}/sessions/...}. Every handler validates
 * {@code session.examId == path.examId}; authorization runs through the same
 * {@link ExamSessionService} as the legacy flat routes.
 */
@RestController
@RequestMapping("/api/exams/{examId}/sessions")
public class ExamSessionNestedController {

    private final ExamSessionService sessionService;

    public ExamSessionNestedController(ExamSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<ExamSessionDetailResponse> createSession(
            @PathVariable Long examId,
            @Valid @RequestBody CreateExamSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionService.createSession(userId, examId, request));
    }

    @GetMapping
    public PageResponse<ExamSessionListItem> listSessions(
            @PathVariable Long examId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.listSessionsForExam(userId, search, status, examId, page, size, sort);
    }

    @GetMapping("/{sessionId}")
    public ExamSessionDetailResponse getSessionDetail(
            @PathVariable Long examId,
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.getSessionDetail(userId, examId, sessionId);
    }

    @PutMapping("/{sessionId}")
    public ExamSessionDetailResponse updateSession(
            @PathVariable Long examId,
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateExamSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.updateSession(userId, examId, sessionId, request);
    }

    @PostMapping("/{sessionId}/schedule")
    public ExamSessionDetailResponse scheduleSession(
            @PathVariable Long examId, @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.scheduleSession(Long.valueOf(jwt.getSubject()), examId, sessionId);
    }

    @PostMapping("/{sessionId}/open")
    public ExamSessionDetailResponse openSession(
            @PathVariable Long examId, @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.openSession(Long.valueOf(jwt.getSubject()), examId, sessionId);
    }

    @PostMapping("/{sessionId}/close")
    public ExamSessionDetailResponse closeSession(
            @PathVariable Long examId, @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.closeSession(Long.valueOf(jwt.getSubject()), examId, sessionId);
    }

    @PostMapping("/{sessionId}/cancel")
    public ExamSessionDetailResponse cancelSession(
            @PathVariable Long examId, @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.cancelSession(Long.valueOf(jwt.getSubject()), examId, sessionId);
    }

    @PutMapping("/{sessionId}/classes")
    public SessionClassesResponse assignClasses(
            @PathVariable Long examId, @PathVariable Long sessionId,
            @RequestBody(required = false) List<Long> classroomIds,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.assignClasses(Long.valueOf(jwt.getSubject()), examId, sessionId, classroomIds);
    }

    @GetMapping("/{sessionId}/classes")
    public SessionClassesResponse listClasses(
            @PathVariable Long examId, @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.listClasses(Long.valueOf(jwt.getSubject()), examId, sessionId);
    }

    @GetMapping("/{sessionId}/roster")
    public List<SessionRosterItem> listRoster(
            @PathVariable Long examId, @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.listRoster(Long.valueOf(jwt.getSubject()), examId, sessionId);
    }
}
