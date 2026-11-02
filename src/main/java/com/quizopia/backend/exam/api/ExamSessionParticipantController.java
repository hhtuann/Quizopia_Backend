package com.quizopia.backend.exam.api;

import com.quizopia.backend.exam.application.ExamSessionParticipantService;
import com.quizopia.backend.exam.dto.AddParticipantsRequest;
import com.quizopia.backend.exam.dto.AddParticipantsResponse;
import com.quizopia.backend.exam.dto.ExamSessionParticipantResponse;
import com.quizopia.backend.question.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/exam-sessions/{sessionId}/participants")
public class ExamSessionParticipantController {

    private final ExamSessionParticipantService participantService;

    public ExamSessionParticipantController(ExamSessionParticipantService participantService) {
        this.participantService = participantService;
    }

    @PostMapping
    public AddParticipantsResponse addParticipants(
            @PathVariable Long sessionId,
            @Valid @RequestBody AddParticipantsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return participantService.addParticipants(userId, sessionId, request);
    }

    @GetMapping
    public PageResponse<ExamSessionParticipantResponse> listParticipants(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String status) {
        Long userId = Long.valueOf(jwt.getSubject());
        return participantService.listParticipants(userId, sessionId, status, page, size, sort);
    }

    @PostMapping("/{participantId}/block")
    public ExamSessionParticipantResponse blockParticipant(
            @PathVariable Long sessionId,
            @PathVariable Long participantId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return participantService.blockParticipant(userId, sessionId, participantId);
    }

    @PostMapping("/{participantId}/unblock")
    public ExamSessionParticipantResponse unblockParticipant(
            @PathVariable Long sessionId,
            @PathVariable Long participantId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return participantService.unblockParticipant(userId, sessionId, participantId);
    }

    // ============================================================
    // User-based block/unblock (V17, issue #4 P0-R8) — the canonical way to
    // moderate PUBLIC sessions; works for profile-less participants.
    // ============================================================

    @PostMapping("/by-user/{targetUserId}/block")
    public ExamSessionParticipantResponse blockUser(
            @PathVariable Long sessionId,
            @PathVariable Long targetUserId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return participantService.blockUser(userId, sessionId, targetUserId);
    }

    @PostMapping("/by-user/{targetUserId}/unblock")
    public ExamSessionParticipantResponse unblockUser(
            @PathVariable Long sessionId,
            @PathVariable Long targetUserId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return participantService.unblockUser(userId, sessionId, targetUserId);
    }
}
