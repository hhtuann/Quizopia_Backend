package com.quizopia.backend.community.api;

import com.quizopia.backend.community.application.CommunityReportService;
import com.quizopia.backend.community.application.CommunityService;
import com.quizopia.backend.community.dto.CommunityReportResponse;
import com.quizopia.backend.community.dto.CreatePublicationRequest;
import com.quizopia.backend.community.dto.OwnerPublicationResponse;
import com.quizopia.backend.community.dto.ReportPublicationRequest;
import com.quizopia.backend.community.dto.UpdatePublicationRequest;
import com.quizopia.backend.question.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner lifecycle API for community publications (ADR-006 §4/§7, issue
 * #40): list-own, create, PATCH, publish, unpublish. Authorization is
 * service-layer (deny-by-default DB checks); the security chain only
 * authenticates. Every response carries {@code Cache-Control: no-store}
 * (§7 — no invalidation story exists).
 */
@RestController
@RequestMapping("/api/community")
public class CommunityPublicationController {

    private final CommunityService communityService;
    private final CommunityReportService communityReportService;

    public CommunityPublicationController(CommunityService communityService,
            CommunityReportService communityReportService) {
        this.communityService = communityService;
        this.communityReportService = communityReportService;
    }

    @GetMapping("/me/publications")
    public ResponseEntity<PageResponse<OwnerPublicationResponse>> myPublications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Long userId = Long.valueOf(jwt.getSubject());
        return noStore(communityService.listMyPublications(userId, page, size, sort));
    }

    @PostMapping("/publications")
    public ResponseEntity<OwnerPublicationResponse> createPublication(
            @Valid @RequestBody CreatePublicationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(communityService.createPublication(userId, request));
    }

    @PatchMapping("/publications/{publicationId}")
    public ResponseEntity<OwnerPublicationResponse> updatePublication(
            @PathVariable Long publicationId,
            @Valid @RequestBody UpdatePublicationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return noStore(communityService.updatePublication(userId, publicationId, request));
    }

    @PostMapping("/publications/{publicationId}/publish")
    public ResponseEntity<OwnerPublicationResponse> publishPublication(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return noStore(communityService.publishPublication(userId, publicationId));
    }

    @PostMapping("/publications/{publicationId}/unpublish")
    public ResponseEntity<OwnerPublicationResponse> unpublishPublication(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return noStore(communityService.unpublishPublication(userId, publicationId));
    }

    /**
     * POST /api/community/publications/{id}/reports (ADR-006 §7, #42).
     * 201 on first submission; 200 with the existing OPEN report on
     * dedupe; 404 when the publication is absent/invisible (§4
     * predicate — no state-transition oracle); 429 past the §9 report
     * buckets (5/h user, 20/h IP; remote addr only, never forwarded
     * headers).
     */
    @PostMapping("/publications/{publicationId}/reports")
    public ResponseEntity<CommunityReportResponse> report(
            @PathVariable Long publicationId,
            @Valid @RequestBody ReportPublicationRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        Long userId = Long.valueOf(jwt.getSubject());
        CommunityReportService.SubmittedReport submitted = communityReportService
                .submitReport(userId, publicationId, request, httpRequest.getRemoteAddr());
        return ResponseEntity.status(submitted.created()
                        ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(submitted.report());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
