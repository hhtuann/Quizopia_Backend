package com.quizopia.backend.monitoring.api;

import com.quizopia.backend.monitoring.application.MonitoringDashboardService;
import com.quizopia.backend.monitoring.application.MonitoringReportService;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse;
import com.quizopia.backend.monitoring.dto.MonitoringDashboardDtos.MonitoringOverviewResponse;
import com.quizopia.backend.monitoring.dto.MonitoringDashboardDtos.MonitoringParticipantsResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P4-E unified dashboard + report reads (issues #95/#96, ADR-010 §2.3–2.4):
 *
 * <ul>
 *   <li>{@code GET /api/exam-sessions/{sessionId}/monitoring/overview} —
 *       session KPI counters, each tied to the signal that produced it.</li>
 *   <li>{@code GET /api/exam-sessions/{sessionId}/monitoring/participants}
 *       — cursor-paged roster with SEPARATE camera/AI/recording/evidence
 *       signals per attempt.</li>
 *   <li>{@code GET /api/attempts/{attemptId}/monitoring/report} — the
 *       per-attempt monitoring report (usable after session close).</li>
 * </ul>
 *
 * <p>All authorization is service-layer (permission + session ownership);
 * every response is an observation, never a verdict.
 */
@RestController
public class MonitoringDashboardController {

    private final MonitoringDashboardService dashboardService;
    private final MonitoringReportService reportService;

    public MonitoringDashboardController(MonitoringDashboardService dashboardService,
            MonitoringReportService reportService) {
        this.dashboardService = dashboardService;
        this.reportService = reportService;
    }

    @GetMapping("/api/exam-sessions/{sessionId}/monitoring/overview")
    public MonitoringOverviewResponse overview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId) {
        return dashboardService.overview(Long.valueOf(jwt.getSubject()), sessionId);
    }

    @GetMapping("/api/exam-sessions/{sessionId}/monitoring/participants")
    public MonitoringParticipantsResponse participants(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cameraState,
            @RequestParam(required = false) String reviewStatus) {
        return dashboardService.participants(Long.valueOf(jwt.getSubject()), sessionId,
                cursor, limit, status, cameraState, reviewStatus);
    }

    @GetMapping("/api/attempts/{attemptId}/monitoring/report")
    public AttemptMonitoringReportResponse report(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long attemptId) {
        return reportService.report(Long.valueOf(jwt.getSubject()), attemptId);
    }
}
