package com.quizopia.backend.attempt.api;

import com.quizopia.backend.attempt.application.AttemptQueryService;
import com.quizopia.backend.attempt.dto.AttemptDetailResponse;
import com.quizopia.backend.attempt.dto.MyAttemptsResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only attempt query endpoints (A3.2-2):
 * <ul>
 *   <li>{@code GET /api/attempts/{attemptId}} — single-attempt detail (questions + saved answers).</li>
 *   <li>{@code GET /api/attempts/my} — paginated own-attempt history.</li>
 * </ul>
 * No mutation, no autosave, no submit. Authorization (STUDENT role + effective permission + profile)
 * is enforced inside {@link AttemptQueryService} via {@code AttemptAuthorizationService} (DB-driven,
 * deny-by-default — JWT authorities are not trusted).
 */
@RestController
@RequestMapping("/api/attempts")
public class AttemptQueryController {

    private final AttemptQueryService queryService;

    public AttemptQueryController(AttemptQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{attemptId}")
    public AttemptDetailResponse getDetail(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable Long attemptId) {
        return queryService.getAttemptDetail(Long.valueOf(jwt.getSubject()), attemptId);
    }

    @GetMapping("/my")
    public MyAttemptsResponse getMy(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(value = "page", defaultValue = "0") int page,
                                    @RequestParam(value = "size", defaultValue = "20") int size) {
        return queryService.getMyAttempts(Long.valueOf(jwt.getSubject()), page, size);
    }
}
