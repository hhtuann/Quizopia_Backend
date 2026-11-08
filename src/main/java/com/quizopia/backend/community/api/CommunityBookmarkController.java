package com.quizopia.backend.community.api;

import com.quizopia.backend.community.application.CommunityBookmarkService;
import com.quizopia.backend.community.dto.CommunityBookmarkResponse;
import com.quizopia.backend.community.dto.PublicBookmarkEntry;
import com.quizopia.backend.question.dto.PageResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bookmark shelf API (ADR-006 §7, issue #45):
 * {@code PUT/DELETE /api/community/publications/{id}/bookmark} and the
 * paged {@code GET /api/community/me/bookmarks}. Authorization is
 * service-layer (deny-by-default DB checks — any ACTIVE user holding
 * effective COMMUNITY_BOOKMARK, STUDENT and TEACHER alike); the
 * security chain only authenticates. Every response carries
 * {@code Cache-Control: no-store} (§7 — no invalidation story exists).
 *
 * <p>PUT: 201 on first save, 200 on the idempotent repeat; §4
 * visibility failure is anti-enumeration 404. DELETE: always 200 —
 * absent rows are a normal idempotent outcome. The list page carries
 * the full §7 card for available sources and ONLY
 * {publicationId, savedAt, unavailable:true} for sources that stopped
 * being visible.
 */
@RestController
@RequestMapping("/api/community")
public class CommunityBookmarkController {

    private final CommunityBookmarkService bookmarkService;

    public CommunityBookmarkController(CommunityBookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @PutMapping("/publications/{publicationId}/bookmark")
    public ResponseEntity<CommunityBookmarkResponse> save(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt) {
        CommunityBookmarkService.SavedBookmark saved = bookmarkService
                .saveBookmark(Long.valueOf(jwt.getSubject()), publicationId);
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(new CommunityBookmarkResponse(publicationId, saved.savedAt()));
    }

    @DeleteMapping("/publications/{publicationId}/bookmark")
    public ResponseEntity<Void> delete(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt) {
        bookmarkService.deleteBookmark(Long.valueOf(jwt.getSubject()), publicationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/me/bookmarks")
    public ResponseEntity<PageResponse<PublicBookmarkEntry>> myBookmarks(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<PublicBookmarkEntry> pageResult = bookmarkService
                .listMyBookmarks(Long.valueOf(jwt.getSubject()), page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pageResult);
    }
}
