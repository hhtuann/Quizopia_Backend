package com.quizopia.backend.monitoring.api;

import com.quizopia.backend.monitoring.application.CameraPreflightService;
import com.quizopia.backend.monitoring.dto.CameraStatusResponse;
import com.quizopia.backend.monitoring.dto.MediaTokenResponse;
import com.quizopia.backend.monitoring.dto.PreflightActionRequest;
import com.quizopia.backend.monitoring.dto.PreflightStartResponse;
import com.quizopia.backend.monitoring.dto.PreflightVerifyResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * P4-B camera preflight REST surface (issues #84–#87, ADR-008 D2/D3):
 *
 * <ul>
 *   <li>{@code POST /api/exam-sessions/{sessionId}/monitoring/preflights}
 *       — learner preflight (media identity + student token).</li>
 *   <li>{@code POST /api/exam-sessions/{sessionId}/monitoring/preflights/
 *       {preflightId}/verify} — poll published camera → readiness lease.</li>
 *   <li>{@code POST /api/attempts/{attemptId}/monitoring/media-token} —
 *       learner token renewal for an IN_PROGRESS monitored attempt.</li>
 *   <li>{@code GET  /api/exam-sessions/{sessionId}/monitoring/camera-status}
 *       — teacher camera grid snapshot.</li>
 *   <li>{@code GET  /api/exam-sessions/{sessionId}/monitoring/media-token}
 *       — teacher viewer token.</li>
 *   <li>{@code POST /api/exam-sessions/{sessionId}/monitoring/participants/
 *       {mediaIdentity}/revoke} — teacher camera revocation.</li>
 * </ul>
 *
 * <p>All authorization is service-layer (DB-effective permission codes +
 * ownership), the established deny-by-default pattern. Every response that
 * carries a media token or a readiness lease is served
 * {@code Cache-Control: no-store}.
 */
@RestController
public class MonitoringPreflightController {

    private final CameraPreflightService preflightService;

    public MonitoringPreflightController(CameraPreflightService preflightService) {
        this.preflightService = preflightService;
    }

    @PostMapping("/api/exam-sessions/{sessionId}/monitoring/preflights")
    public ResponseEntity<PreflightStartResponse> createPreflight(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId,
            @RequestBody(required = false) PreflightActionRequest body) {
        PreflightStartResponse response = preflightService.createPreflight(
                Long.valueOf(jwt.getSubject()), sessionId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/api/exam-sessions/{sessionId}/monitoring/preflights/{preflightId}/verify")
    public ResponseEntity<PreflightVerifyResponse> verifyPreflight(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId,
            @PathVariable UUID preflightId,
            @RequestBody(required = false) PreflightActionRequest body) {
        PreflightVerifyResponse response = preflightService.verifyPreflight(
                Long.valueOf(jwt.getSubject()), sessionId, preflightId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/api/attempts/{attemptId}/monitoring/media-token")
    public ResponseEntity<MediaTokenResponse> attemptMediaToken(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long attemptId,
            @RequestBody(required = false) PreflightActionRequest body) {
        MediaTokenResponse response = preflightService.attemptMediaToken(
                Long.valueOf(jwt.getSubject()), attemptId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/api/exam-sessions/{sessionId}/monitoring/camera-status")
    public CameraStatusResponse cameraStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId) {
        return preflightService.cameraStatus(Long.valueOf(jwt.getSubject()), sessionId);
    }

    @GetMapping("/api/exam-sessions/{sessionId}/monitoring/media-token")
    public ResponseEntity<MediaTokenResponse> teacherMediaToken(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId) {
        MediaTokenResponse response = preflightService.teacherMediaToken(
                Long.valueOf(jwt.getSubject()), sessionId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/api/exam-sessions/{sessionId}/monitoring/participants/{mediaIdentity}/revoke")
    public ResponseEntity<Void> revokeParticipant(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId,
            @PathVariable String mediaIdentity,
            @RequestBody(required = false) PreflightActionRequest body) {
        preflightService.revokeParticipant(
                Long.valueOf(jwt.getSubject()), sessionId, mediaIdentity);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
