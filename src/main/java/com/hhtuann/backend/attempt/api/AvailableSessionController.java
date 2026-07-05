package com.hhtuann.backend.attempt.api;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.AvailableSessionsResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exam-sessions")
public class AvailableSessionController {

    private final AttemptService attemptService;

    public AvailableSessionController(AttemptService attemptService) {
        this.attemptService = attemptService;
    }

    @GetMapping("/available")
    public AvailableSessionsResponse getAvailable(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return attemptService.getAvailableSessions(userId);
    }
}
