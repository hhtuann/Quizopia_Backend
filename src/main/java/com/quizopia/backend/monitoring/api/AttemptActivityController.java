package com.quizopia.backend.monitoring.api;

import com.quizopia.backend.monitoring.application.AnswerHistoryService;
import com.quizopia.backend.monitoring.application.AttemptActivityIngestService;
import com.quizopia.backend.monitoring.application.AttemptTimelineService;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchRequest;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchResponse;
import com.quizopia.backend.monitoring.dto.AnswerHistoryResponse;
import com.quizopia.backend.monitoring.dto.AttemptTimelineResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P4-A monitoring REST surface (issues #81/#82/#83):
 *
 * <ul>
 *   <li>{@code POST /api/attempts/{attemptId}/activity-events} — bounded
 *       idempotent client-telemetry batch ingest (owner only).</li>
 *   <li>{@code GET  /api/attempts/{attemptId}/timeline} — cursor-paged
 *       durable unified timeline (owner or session-owner teacher).</li>
 *   <li>{@code GET  /api/attempts/{attemptId}/answer-history} — accepted
 *       answer versions (owner or session-owner teacher).</li>
 * </ul>
 *
 * <p>All authorization is service-layer (permission codes + ownership), the
 * established deny-by-default pattern; errors map via the global handler.
 */
@RestController
@RequestMapping("/api/attempts/{attemptId}")
public class AttemptActivityController {

    private final AttemptActivityIngestService ingestService;
    private final AttemptTimelineService timelineService;
    private final AnswerHistoryService historyService;

    public AttemptActivityController(AttemptActivityIngestService ingestService,
            AttemptTimelineService timelineService,
            AnswerHistoryService historyService) {
        this.ingestService = ingestService;
        this.timelineService = timelineService;
        this.historyService = historyService;
    }

    @PostMapping("/activity-events")
    public ResponseEntity<ActivityEventBatchResponse> ingest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long attemptId,
            @Valid @RequestBody ActivityEventBatchRequest request) {
        ActivityEventBatchResponse response = ingestService.ingest(
                Long.valueOf(jwt.getSubject()), attemptId, request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping("/timeline")
    public AttemptTimelineResponse timeline(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long attemptId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return timelineService.timeline(Long.valueOf(jwt.getSubject()), attemptId,
                cursor, limit, types, source, from, to);
    }

    @GetMapping("/answer-history")
    public AnswerHistoryResponse answerHistory(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long attemptId,
            @RequestParam(required = false) Long attemptQuestionId,
            @RequestParam(required = false) Integer limit) {
        return historyService.history(Long.valueOf(jwt.getSubject()), attemptId,
                attemptQuestionId, limit);
    }
}
