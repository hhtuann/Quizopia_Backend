package com.hhtuann.backend.exam.api;

import com.hhtuann.backend.exam.application.ExamSessionService;
import com.hhtuann.backend.exam.dto.AssignSessionClassesRequest;
import com.hhtuann.backend.exam.dto.ExamSessionDetailResponse;
import com.hhtuann.backend.exam.dto.ExamSessionListItem;
import com.hhtuann.backend.exam.dto.CreateExamSessionRequest;
import com.hhtuann.backend.exam.dto.SessionClassesResponse;
import com.hhtuann.backend.exam.dto.UpdateExamSessionRequest;
import com.hhtuann.backend.question.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/exam-sessions")
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
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.createSession(userId, request));
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
        return sessionService.listMySessions(userId, search, status, examId, page, size, sort);
    }

    @GetMapping("/{sessionId}")
    public ExamSessionDetailResponse getSessionDetail(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.getSessionDetail(userId, sessionId);
    }

    @PutMapping("/{sessionId}")
    public ExamSessionDetailResponse updateSession(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateExamSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.updateSession(userId, sessionId, request);
    }

    @PostMapping("/{sessionId}/schedule")
    public ExamSessionDetailResponse scheduleSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.scheduleSession(userId, sessionId);
    }

    @PostMapping("/{sessionId}/open")
    public ExamSessionDetailResponse openSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.openSession(userId, sessionId);
    }

    @PostMapping("/{sessionId}/close")
    public ExamSessionDetailResponse closeSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.closeSession(userId, sessionId);
    }

    @PostMapping("/{sessionId}/cancel")
    public ExamSessionDetailResponse cancelSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.cancelSession(userId, sessionId);
    }

    @PutMapping("/{sessionId}/classes")
    public SessionClassesResponse assignClasses(
            @PathVariable Long sessionId,
            @RequestBody AssignSessionClassesRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.assignClasses(userId, sessionId, request.classroomIds());
    }

    @GetMapping("/{sessionId}/classes")
    public SessionClassesResponse listClasses(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return sessionService.listClasses(userId, sessionId);
    }
}
