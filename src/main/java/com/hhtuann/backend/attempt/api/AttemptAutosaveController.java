package com.hhtuann.backend.attempt.api;

import com.hhtuann.backend.attempt.application.AttemptAutosaveService;
import com.hhtuann.backend.attempt.dto.SaveAnswerRequest;
import com.hhtuann.backend.attempt.dto.SaveAnswerResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Autosave endpoint (A3.2-3): {@code PUT /api/attempts/{attemptId}/answers}.
 *
 * <p>Authorization (STUDENT role + {@code ATTEMPT_ANSWER_SAVE} + ACTIVE profile) and the
 * state/deadline/ownership/payload/sequence rules are enforced in {@link AttemptAutosaveService}.
 * No submit, no grading, no event.
 */
@RestController
@RequestMapping("/api/attempts")
public class AttemptAutosaveController {

    private final AttemptAutosaveService autosaveService;

    public AttemptAutosaveController(AttemptAutosaveService autosaveService) {
        this.autosaveService = autosaveService;
    }

    @PutMapping("/{attemptId}/answers")
    public SaveAnswerResponse saveAnswer(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable Long attemptId,
                                         @RequestBody SaveAnswerRequest request) {
        return autosaveService.saveAnswer(Long.valueOf(jwt.getSubject()), attemptId, request);
    }
}
