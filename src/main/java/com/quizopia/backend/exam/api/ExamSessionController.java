package com.quizopia.backend.exam.api;

import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.exam.dto.AssignSessionClassesRequest;
import com.quizopia.backend.exam.dto.ExamSessionDetailResponse;
import com.quizopia.backend.exam.dto.ExamSessionListItem;
import com.quizopia.backend.exam.dto.CreateExamSessionRequest;
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
 * LEGACY flat session routes — retained temporarily for existing teacher
 * clients, student entry points and mobile/external consumers. Every handler
 * delegates to the same {@link ExamSessionService} as the canonical nested
 * routes ({@code /api/exams/{examId}/sessions/...}); there is no duplicated
 * business logic. Deprecate after a usage audit (issue #2 §4).
 */
@RestController
@RequestMapping("/api/exam-sessions")
@Deprecated
public class ExamSessionController {

    private final ExamSessionService sessionService;

    public ExamSessionController(ExamSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<ExamSessionDetailResponse> createSession(
            @Valid @RequestBody CreateExamSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionService.createSession(userId, null, request));
    }

    @GetMapping("/my")
    public PageResponse<ExamSessionListItem> mySessions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long examId) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.listSessions(userId, search, status, examId, page, size, sort);
    }

    @GetMapping("/{sessionId}")
    public ExamSessionDetailResponse getSessionDetail(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.getSessionDetail(userId, null, sessionId);
    }

    @PutMapping("/{sessionId}")
    public ExamSessionDetailResponse updateSession(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateExamSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.updateSession(userId, null, sessionId, request);
    }

    @PostMapping("/{sessionId}/schedule")
    public ExamSessionDetailResponse scheduleSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.scheduleSession(Long.valueOf(jwt.getSubject()), null, sessionId);
    }

    @PostMapping("/{sessionId}/open")
    public ExamSessionDetailResponse openSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.openSession(Long.valueOf(jwt.getSubject()), null, sessionId);
    }

    @PostMapping("/{sessionId}/close")
    public ExamSessionDetailResponse closeSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.closeSession(Long.valueOf(jwt.getSubject()), null, sessionId);
    }

    @PostMapping("/{sessionId}/cancel")
    public ExamSessionDetailResponse cancelSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.cancelSession(Long.valueOf(jwt.getSubject()), null, sessionId);
    }

    @PutMapping("/{sessionId}/classes")
    public SessionClassesResponse assignClasses(
            @PathVariable Long sessionId,
            @RequestBody AssignSessionClassesRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.assignClasses(Long.valueOf(jwt.getSubject()), null, sessionId,
                request.classroomIds());
    }

    @GetMapping("/{sessionId}/classes")
    public SessionClassesResponse listClasses(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.listClasses(Long.valueOf(jwt.getSubject()), null, sessionId);
    }

    @GetMapping("/{sessionId}/roster")
    public List<SessionRosterItem> listRoster(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return sessionService.listRoster(Long.valueOf(jwt.getSubject()), null, sessionId);
    }
}
