package com.quizopia.backend.attempt.api;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.AvailableSessionsResponse;
import com.quizopia.backend.attempt.dto.SessionEligibilityResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exam-sessions")
public class AvailableSessionController {

    private final AttemptService attemptService;

    public AvailableSessionController(AttemptService attemptService) {
        this.attemptService = attemptService;
    }

    /**
     * Participant pre-flight for a shared session link (P2-D, issue #20):
     * server-authorized state for one session — same eligibility policy as
     * discovery/start (visibility, classroom membership, BLOCKED,
     * lifecycle, window, attempt quota), WITHOUT starting an attempt and
     * without creator-only metadata. See
     * {@link AttemptService#getSessionEligibility(Long, Long)}.
     */
    @GetMapping("/{sessionId}/eligibility")
    public SessionEligibilityResponse getEligibility(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return attemptService.getSessionEligibility(userId, sessionId);
    }

    @GetMapping("/available")
    public AvailableSessionsResponse getAvailable(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return attemptService.getAvailableSessions(userId);
    }
}
