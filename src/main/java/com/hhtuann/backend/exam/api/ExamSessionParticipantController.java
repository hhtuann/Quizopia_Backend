package com.hhtuann.backend.exam.api;

import com.hhtuann.backend.exam.application.ExamSessionParticipantService;
import com.hhtuann.backend.exam.dto.AddParticipantsRequest;
import com.hhtuann.backend.exam.dto.AddParticipantsResponse;
import com.hhtuann.backend.exam.dto.ExamSessionParticipantResponse;
import com.hhtuann.backend.question.dto.PageResponse;
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
}
