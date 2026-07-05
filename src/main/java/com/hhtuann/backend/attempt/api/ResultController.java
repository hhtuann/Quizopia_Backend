package com.hhtuann.backend.attempt.api;

import com.hhtuann.backend.attempt.application.AttemptResultService;
import com.hhtuann.backend.attempt.application.ExcelExportService;
import com.hhtuann.backend.attempt.application.SessionResultService;
import com.hhtuann.backend.attempt.application.SessionStatisticsService;
import com.hhtuann.backend.attempt.dto.AttemptResultResponse;
import com.hhtuann.backend.attempt.dto.SessionStatisticsResponse;
import com.hhtuann.backend.attempt.exception.AttemptErrorCode;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.security.authentication.EffectiveRoleResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Day 8 result endpoints. Role is derived from effective Authentication authorities (loaded by
 * {@link com.hhtuann.backend.security.authentication.QuizopiaJwtAuthenticationConverter} from the DB),
 * NOT from the JWT {@code roles} claim. Unsupported roles are denied (fail-closed) at the service layer.
 */
@RestController
public class ResultController {

    private final AttemptResultService resultService;
    private final SessionResultService sessionResultService;
    private final SessionStatisticsService statisticsService;
    private final ExcelExportService exportService;

    public ResultController(AttemptResultService resultService, SessionResultService sessionResultService,
                           SessionStatisticsService statisticsService, ExcelExportService exportService) {
        this.resultService = resultService;
        this.sessionResultService = sessionResultService;
        this.statisticsService = statisticsService;
        this.exportService = exportService;
    }

    /** Role-aware attempt result detail (service enforces ownership per role). */
    @GetMapping("/api/attempts/{attemptId}/result")
    public AttemptResultResponse getAttemptResult(@AuthenticationPrincipal Jwt jwt,
                                                  Authentication authentication,
                                                  @PathVariable Long attemptId) {
        return resultService.getAttemptResult(Long.valueOf(jwt.getSubject()),
                EffectiveRoleResolver.resolve(authentication.getAuthorities()), attemptId);
    }

    /** Student own BEST result for a session. */
    @GetMapping("/api/exam-sessions/{sessionId}/results/me/best")
    public AttemptResultResponse getMyBestResult(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable Long sessionId) {
        return resultService.getMyBestResult(Long.valueOf(jwt.getSubject()), sessionId);
    }

    /** Teacher/admin paginated session results — one BEST row per student (CTE, database pagination). */
    @GetMapping("/api/exam-sessions/{sessionId}/results")
    public SessionResultService.SessionResultsPage getSessionResults(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) BigDecimal minPercentage,
            @RequestParam(required = false) BigDecimal maxPercentage) {
        return sessionResultService.getSessionResults(
                Long.valueOf(jwt.getSubject()),
                EffectiveRoleResolver.resolve(authentication.getAuthorities()), sessionId,
                page, size, sort, direction, search, minPercentage, maxPercentage);
    }

    /** Teacher/admin session statistics (BEST-based). */
    @GetMapping("/api/exam-sessions/{sessionId}/statistics")
    public SessionStatisticsResponse getStatistics(@AuthenticationPrincipal Jwt jwt,
                                                    Authentication authentication,
                                                    @PathVariable Long sessionId) {
        return statisticsService.getStatistics(Long.valueOf(jwt.getSubject()),
                EffectiveRoleResolver.resolve(authentication.getAuthorities()), sessionId);
    }

    /** Teacher/admin XLSX export (authorization enforced before workbook creation). */
    @GetMapping("/api/exam-sessions/{sessionId}/results/export")
    public ResponseEntity<byte[]> exportResults(@AuthenticationPrincipal Jwt jwt,
                                                 Authentication authentication,
                                                 @PathVariable Long sessionId) {
        Long userId = Long.valueOf(jwt.getSubject());
        String role = EffectiveRoleResolver.resolve(authentication.getAuthorities());
        byte[] bytes = exportService.export(userId, role, sessionId);
        String filename = ExcelExportService.filename(sessionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, ExcelExportService.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
