package com.quizopia.backend.community.application;

import com.quizopia.backend.community.domain.model.CommunityReport;
import com.quizopia.backend.community.domain.model.CommunityReportReason;
import com.quizopia.backend.community.dto.CommunityReportResponse;
import com.quizopia.backend.community.dto.ReportPublicationRequest;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.community.repository.CommunityReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Report submission (ADR-006 §7, issue #42). The §7 FIXED ORDER is the
 * contract — do not reorder:
 *
 * <ol>
 *   <li><b>semantic validation</b> — bounded reasonCode enum + details
 *       ≤500 (bean validation already rejected malformed bodies before
 *       any admission);</li>
 *   <li><b>rate admission for every syntactically valid request</b> —
 *       user bucket (5/h) then IP bucket (20/h), before any
 *       authorization read or publication probe (§9: denied requests
 *       still consume, bucket locks stay leaf-most);</li>
 *   <li><b>§4 visibility predicate → 404</b> — reporting a hidden,
 *       unpublished, archived-source or absent publication is
 *       anti-enumeration 404 (re-reporting a since-hidden publication
 *       404s: no state-transition oracle for reporters);</li>
 *   <li><b>OPEN dedupe → 200</b> returning the existing report —
 *       {@code INSERT … ON CONFLICT … DO NOTHING} + re-read (partial
 *       index inference), never a caught constraint violation; a
 *       single retry covers a concurrent dismissal emptying the
 *       re-read;</li>
 * </ol>
 */
@Service
public class CommunityReportService {

    static final String PERMISSION_REPORT = "COMMUNITY_REPORT";

    private final CommunityReportRepository reports;
    private final CommunityAuthorizationService auth;
    private final CommunityRateLimiter rateLimiter;
    private final CommunityVisibilityStore visibilityStore;
    private final Clock clock;

    public CommunityReportService(CommunityReportRepository reports,
            CommunityAuthorizationService auth,
            CommunityRateLimiter rateLimiter,
            CommunityVisibilityStore visibilityStore,
            Clock clock) {
        this.reports = reports;
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.visibilityStore = visibilityStore;
        this.clock = clock;
    }

    /** Submission outcome: the report body plus the 201-vs-200 flag. */
    public record SubmittedReport(CommunityReportResponse report, boolean created) {
    }

    @Transactional
    public SubmittedReport submitReport(Long userId, Long publicationId,
            ReportPublicationRequest request, String remoteAddr) {
        // (0) Semantic validation — bounded enum; garbage never admits.
        CommunityReportReason reason = CommunityReportReason.parse(request.reasonCode());
        if (reason == null) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "reasonCode must be one of INAPPROPRIATE_CONTENT, COPYRIGHT, MISLEADING, SPAM, OTHER");
        }
        String details = request.details() == null ? null : request.details().trim();
        if (details != null && details.isEmpty()) {
            details = null;
        }
        if (details != null && details.length() > 500) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "details must be at most 500 characters");
        }

        // (1) Rate admission — user bucket first, then IP bucket (§9
        // fixed multi-bucket order); the FIRST statement touching state.
        rateLimiter.admitUser(CommunityRateLimiter.Policy.REPORT_USER, userId);
        rateLimiter.admitIp(CommunityRateLimiter.Policy.REPORT_IP, remoteAddr);

        // Gate: any ACTIVE user + effective COMMUNITY_REPORT (STUDENT and
        // TEACHER both hold it; role-less callers are 403).
        auth.requireActiveUserWithPermission(userId, PERMISSION_REPORT);

        // (2) §4 visibility predicate → 404.
        if (!visibilityStore.isVisible(publicationId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND);
        }

        // (3)+(4) Dedupe insert + re-read: 1 affected row → this call
        // created the OPEN report (201); 0 → an OPEN report already
        // existed (200 with that report, same id either way). A
        // concurrent dismissal can land BETWEEN the 0-row insert and the
        // re-read, leaving the re-read empty through no fault of this
        // caller — retry the pair exactly once (the second insert now
        // wins the partial index). Still empty means no OPEN report can
        // exist for this caller: anti-enumeration 404, never a 500.
        int inserted = reports.insertIfNoOpenReport(
                publicationId, userId, reason.name(), details, Instant.now(clock));
        CommunityReport report = reports
                .findOpenByReporterAndPublication(userId, publicationId).orElse(null);
        if (report == null) {
            inserted = reports.insertIfNoOpenReport(
                    publicationId, userId, reason.name(), details, Instant.now(clock));
            report = reports
                    .findOpenByReporterAndPublication(userId, publicationId)
                    .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));
        }
        return new SubmittedReport(toResponse(report), inserted == 1);
    }

    private static CommunityReportResponse toResponse(CommunityReport report) {
        return new CommunityReportResponse(
                report.getId(),
                report.getPublicationId(),
                report.getReporterUserId(),
                report.getReasonCode().name(),
                report.getDetails(),
                report.getStatus().name(),
                report.getCreatedAt());
    }
}
