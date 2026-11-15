package com.quizopia.backend.monitoring.api;

import com.quizopia.backend.monitoring.application.AiAlertQueryService;
import com.quizopia.backend.monitoring.application.AiDetectionIngestService;
import com.quizopia.backend.monitoring.dto.AiAlertPageResponse;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchRequest;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P4-C AI proctoring REST surface (issues #88/#90, ADR-008 amendment):
 *
 * <ul>
 *   <li>{@code POST /api/attempts/{attemptId}/ai-detections} — bounded
 *       idempotent browser-model detection batches (attempt owner only,
 *       {@code AI_DETECTION_INGEST}; provenance CLIENT_MODEL).</li>
 *   <li>{@code GET  /api/exam-sessions/{sessionId}/ai-alerts} —
 *       teacher-only, cursor-paged newest-first alert backfill
 *       ({@code AI_ALERTS_READ} + session owner); students have no read
 *       path.</li>
 * </ul>
 *
 * <p>All authorization is service-layer (permission codes + ownership),
 * the established deny-by-default pattern; errors map via the global
 * handler. The internal worker endpoint lives in
 * {@link AiWorkerIngestController} (X-Api-Key, not JWT).
 */
@RestController
public class AiDetectionController {

    private final AiDetectionIngestService ingestService;
    private final AiAlertQueryService alertQueryService;

    public AiDetectionController(AiDetectionIngestService ingestService,
            AiAlertQueryService alertQueryService) {
        this.ingestService = ingestService;
        this.alertQueryService = alertQueryService;
    }

    @PostMapping("/api/attempts/{attemptId}/ai-detections")
    public ResponseEntity<AiDetectionBatchResponse> ingest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long attemptId,
            @Valid @RequestBody AiDetectionBatchRequest request) {
        AiDetectionBatchResponse response = ingestService.ingestStudent(
                Long.valueOf(jwt.getSubject()), attemptId, request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping("/api/exam-sessions/{sessionId}/ai-alerts")
    public AiAlertPageResponse alerts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false) String status) {
        return alertQueryService.alerts(Long.valueOf(jwt.getSubject()), sessionId,
                cursor, limit, status);
    }
}
