package com.quizopia.backend.attempt.api;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.dto.StartAttemptResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exam-sessions")
public class AttemptController {

    private final AttemptService attemptService;

    public AttemptController(AttemptService attemptService) {
        this.attemptService = attemptService;
    }

    @PostMapping("/{sessionId}/attempts")
    public ResponseEntity<StartAttemptResponse> startAttempt(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId,
            @RequestBody(required = false) StartAttemptRequest request) {
        Long userId = Long.valueOf(jwt.getSubject());
        StartAttemptRequest req = request != null ? request : new StartAttemptRequest(null);
        StartAttemptResponse response = attemptService.startAttempt(userId, sessionId, req);
        HttpStatus status = response.resumed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
