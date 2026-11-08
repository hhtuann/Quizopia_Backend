package com.quizopia.backend.community.application;

import com.quizopia.backend.community.domain.model.CommunityBookmark;
import com.quizopia.backend.community.dto.PublicBookmarkEntry;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.community.repository.CommunityBookmarkRepository;
import com.quizopia.backend.question.dto.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Bookmark shelf (ADR-006 §7, issue #45). PUT/DELETE follow the §7/§9
 * order shared with #42's report submission — do not reorder:
 *
 * <ol>
 *   <li><b>rate admission</b> — user bucket (BOOKMARK policy, 60/h in
 *       production) as the FIRST statement of the write transaction,
 *       before any authorization read or publication probe (§9: denied
 *       requests still consume, bucket locks stay leaf-most);</li>
 *   <li><b>permission gate</b> — any ACTIVE user holding effective
 *       COMMUNITY_BOOKMARK (STUDENT and TEACHER both qualify; role-less
 *       callers are 403);</li>
 *   <li><b>PUT only: §4 visibility predicate → 404</b> — saving an
 *       invisible/absent publication is anti-enumeration 404, so a
 *       bookmark can never become an existence oracle;</li>
 *   <li><b>upsert</b> — {@code INSERT … ON CONFLICT (user_id,
 *       publication_id) DO NOTHING} + re-select: 1 affected row → 201,
 *       0 → 200 with the existing row (same id/savedAt either way,
 *       concurrent savers included — never a caught constraint
 *       violation).</li>
 * </ol>
 *
 * <p>DELETE skips the visibility probe entirely (a bookmark is removed
 * whatever happened to its source) and is idempotent by shape: absent
 * → 200. Bookmarks never grant read rights beyond public metadata —
 * the shelf is listed through the §4-predicate page in
 * {@link CommunityVisibilityStore#findBookmarkedPage}, where sources
 * that stopped being visible surface as the three-field
 * {publicationId, savedAt, unavailable:true} placeholder ONLY.
 */
@Service
public class CommunityBookmarkService {

    static final String PERMISSION_BOOKMARK = "COMMUNITY_BOOKMARK";
    static final int MAX_PAGE_SIZE = 50;
    static final String BOOKMARK_SORT = "savedAt DESC, id DESC";

    private final CommunityBookmarkRepository bookmarks;
    private final CommunityAuthorizationService auth;
    private final CommunityRateLimiter rateLimiter;
    private final CommunityVisibilityStore visibilityStore;
    private final Clock clock;

    public CommunityBookmarkService(CommunityBookmarkRepository bookmarks,
            CommunityAuthorizationService auth,
            CommunityRateLimiter rateLimiter,
            CommunityVisibilityStore visibilityStore,
            Clock clock) {
        this.bookmarks = bookmarks;
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.visibilityStore = visibilityStore;
        this.clock = clock;
    }

    /** Save outcome: the persisted identity plus the 201-vs-200 flag. */
    public record SavedBookmark(long publicationId, Instant savedAt, boolean created) {
    }

    @Transactional
    public SavedBookmark saveBookmark(Long userId, Long publicationId) {
        // (1) §9 admission — first statement of the write transaction.
        rateLimiter.admitUser(CommunityRateLimiter.Policy.BOOKMARK, userId);

        // (2) Permission gate: ACTIVE + effective COMMUNITY_BOOKMARK.
        auth.requireActiveUserWithPermission(userId, PERMISSION_BOOKMARK);

        // (3) §4 visibility predicate → 404 (never an existence oracle).
        if (!visibilityStore.isVisible(publicationId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND);
        }

        // (4) Idempotent upsert + re-select: 1 affected row → this call
        // created the bookmark (201); 0 → it already existed (200, same
        // row — pre-existing or committed concurrently).
        int inserted = bookmarks.insertIfAbsent(userId, publicationId, Instant.now(clock));
        CommunityBookmark bookmark = bookmarks.findByUserIdAndPublicationId(userId, publicationId)
                .orElseThrow(() -> new IllegalStateException(
                        "bookmark missing after upsert for publication " + publicationId));
        return new SavedBookmark(publicationId, bookmark.getCreatedAt(), inserted == 1);
    }

    @Transactional
    public void deleteBookmark(Long userId, Long publicationId) {
        // Same §9 order: admission, then permission. No visibility
        // probe — un-saving works whatever became of the source.
        rateLimiter.admitUser(CommunityRateLimiter.Policy.BOOKMARK, userId);
        auth.requireActiveUserWithPermission(userId, PERMISSION_BOOKMARK);

        bookmarks.deleteByUserIdAndPublicationId(userId, publicationId);
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicBookmarkEntry> listMyBookmarks(Long userId, int page, int size) {
        auth.requireActiveUserWithPermission(userId, PERMISSION_BOOKMARK);

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        CommunityVisibilityStore.BookmarkedPage result =
                visibilityStore.findBookmarkedPage(userId, safePage, safeSize);
        int totalPages = (int) ((result.totalElements() + safeSize - 1) / safeSize);
        return new PageResponse<>(result.entries(), safePage, safeSize,
                result.totalElements(), totalPages, BOOKMARK_SORT);
    }
}
