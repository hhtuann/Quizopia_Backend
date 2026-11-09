package com.quizopia.backend.community.api;

import com.quizopia.backend.community.application.PublicCommunityService;
import com.quizopia.backend.community.application.PublicCommunityService.PublicReadCaller;
import com.quizopia.backend.community.dto.PublicCategoryResponse;
import com.quizopia.backend.community.dto.PublicPublicationCard;
import com.quizopia.backend.community.dto.PublicPublicationDetail;
import com.quizopia.backend.community.dto.PublicSocialSummaryResponse;
import com.quizopia.backend.community.dto.PublicTagUsage;
import com.quizopia.backend.question.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Anonymous public discovery API (ADR-006 §7, issue #41). The four exact
 * GET routes under {@code /api/public/community} are permitAll in the
 * security chain; every other method on these paths is caught by the
 * deny-by-default chain (401 anonymous). Eligibility filtering is the §4
 * predicate in {@code CommunityVisibilityStore} — invisible publications
 * are 404 anti-enumeration with a body identical to a random id.
 *
 * <p>Rate admission (§9): anonymous callers against the
 * {@code ip:<servlet-remote-addr>} bucket — forwarded headers are never
 * trusted; authenticated callers against their personal user bucket.
 * Every response carries {@code Cache-Control: no-store} (§7).
 */
@RestController
@RequestMapping("/api/public/community")
public class PublicCommunityController {

    private final PublicCommunityService publicCommunityService;

    public PublicCommunityController(PublicCommunityService publicCommunityService) {
        this.publicCommunityService = publicCommunityService;
    }

    @GetMapping("/publications")
    public ResponseEntity<PageResponse<PublicPublicationCard>> catalog(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return noStore(publicCommunityService.catalog(
                caller(jwt, request), q, categoryId, tags, page, size));
    }

    @GetMapping("/publications/{publicationId}")
    public ResponseEntity<PublicPublicationDetail> detail(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return noStore(publicCommunityService.detail(caller(jwt, request), publicationId));
    }

    @GetMapping("/publications/{publicationId}/social-summary")
    public ResponseEntity<PublicSocialSummaryResponse> socialSummary(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return noStore(publicCommunityService.socialSummary(
                caller(jwt, request), publicationId));
    }

    @GetMapping("/categories")
    public ResponseEntity<List<PublicCategoryResponse>> categories(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return noStore(publicCommunityService.categories(caller(jwt, request)));
    }

    @GetMapping("/tags")
    public ResponseEntity<List<PublicTagUsage>> tags(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return noStore(publicCommunityService.tags(caller(jwt, request)));
    }

    /** Anonymous callers are IP-keyed; a valid JWT switches to the user bucket (§9). */
    private static PublicReadCaller caller(Jwt jwt, HttpServletRequest request) {
        Long userId = jwt == null || jwt.getSubject() == null ? null : Long.valueOf(jwt.getSubject());
        return new PublicReadCaller(userId, request.getRemoteAddr());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
