package com.quizopia.backend.community.application;

import com.quizopia.backend.community.application.CommunityVisibilityStore.CatalogFilter;
import com.quizopia.backend.community.application.CommunityVisibilityStore.CatalogPage;
import com.quizopia.backend.community.dto.PublicCategoryResponse;
import com.quizopia.backend.community.dto.PublicPublicationCard;
import com.quizopia.backend.community.dto.PublicPublicationDetail;
import com.quizopia.backend.community.dto.PublicTagUsage;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.community.repository.CommunityCategoryRepository;
import com.quizopia.backend.question.dto.PageResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Anonymous public discovery service (ADR-006 §7, issue #41): catalog
 * page, detail, categories and tag usage counts — all visibility-filtered
 * through the single §4 predicate in {@link CommunityVisibilityStore}.
 *
 * <p>Rate admission (§9) runs FIRST for every request: anonymous callers
 * against the {@code ip:<remote-addr>} bucket (PUBLIC_READ_IP policy,
 * 6000/h); authenticated callers against their personal user bucket —
 * never the shared NAT IP bucket. Admission uses the limiter's
 * self-committing REQUIRES_NEW path, so these read-only requests carry no
 * surrounding transaction (anonymous access never depends on one).
 *
 * <p>Parameter policy: {@code q} trimmed and clamped to 100 characters
 * with {@code %}/{@code _} escaped for parametrized ILIKE; tags
 * lower-cased/trimmed (AND semantics — more than 5 distinct tags can
 * never match a publication, which is capped at 5); {@code page} clamped
 * {@code >= 0}, {@code size} clamped to 1..50 (default 20).
 */
@Service
public class PublicCommunityService {

    /** ADR-006 §7: search bound on {@code q}. */
    static final int MAX_QUERY_LENGTH = 100;
    /** ADR-006 §3/§7: publications carry at most 5 tags — filters beyond that are provably empty. */
    static final int MAX_PUBLICATION_TAGS = 5;
    static final int MAX_PAGE_SIZE = 50;
    static final int DEFAULT_PAGE_SIZE = 20;
    /** ADR-006 §7: top-100 tag usage list. */
    static final int TAG_USAGE_LIMIT = 100;

    /** Caller identity for admission: JWT subject when present, else servlet remote addr. */
    public record PublicReadCaller(Long userId, String remoteAddr) {
    }

    private final CommunityVisibilityStore visibilityStore;
    private final CommunityCategoryRepository categories;
    private final CommunityRateLimiter rateLimiter;

    public PublicCommunityService(CommunityVisibilityStore visibilityStore,
            CommunityCategoryRepository categories,
            CommunityRateLimiter rateLimiter) {
        this.visibilityStore = visibilityStore;
        this.categories = categories;
        this.rateLimiter = rateLimiter;
    }

    /** GET /api/public/community/publications */
    public PageResponse<PublicPublicationCard> catalog(PublicReadCaller caller,
            String q, Long categoryId, List<String> tags, int page, int size) {
        admit(caller);

        String boundedQuery = boundQuery(q);
        List<String> normalizedTags = normalizeTags(tags);

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        if (normalizedTags.size() > MAX_PUBLICATION_TAGS) {
            // AND semantics over at most 5 tags per publication: provably empty.
            return new PageResponse<>(List.of(), safePage, safeSize, 0L, 0, "publishedAt DESC, id DESC");
        }

        CatalogPage result = visibilityStore.findVisibleCatalog(
                new CatalogFilter(boundedQuery, categoryId, normalizedTags), safePage, safeSize);
        int totalPages = (int) ((result.totalElements() + safeSize - 1) / safeSize);
        return new PageResponse<>(result.items(), safePage, safeSize,
                result.totalElements(), totalPages, "publishedAt DESC, id DESC");
    }

    /** GET /api/public/community/publications/{id} — invisible = 404 anti-enumeration. */
    public PublicPublicationDetail detail(PublicReadCaller caller, long publicationId) {
        admit(caller);
        return visibilityStore.findVisibleDetail(publicationId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));
    }

    /** GET /api/public/community/categories — [{id,code,title}] ordered by position (§7 Rev 4). */
    public List<PublicCategoryResponse> categories(PublicReadCaller caller) {
        admit(caller);
        return categories.findAllByOrderByPositionAscCodeAsc().stream()
                .map(c -> new PublicCategoryResponse(c.getId(), c.getCode(), c.getTitle()))
                .toList();
    }

    /** GET /api/public/community/tags — top 100 [{label,usageCount}], visible sources only. */
    public List<PublicTagUsage> tags(PublicReadCaller caller) {
        admit(caller);
        return visibilityStore.findVisibleTagUsage(TAG_USAGE_LIMIT);
    }

    // ============================================================
    // internals
    // ============================================================

    /**
     * §9 admission, first statement of every public read: anonymous →
     * ip:<remote-addr> bucket; authenticated → user bucket (never the
     * shared NAT IP bucket). Self-committing; no enclosing transaction.
     */
    private void admit(PublicReadCaller caller) {
        if (caller.userId() != null) {
            rateLimiter.admitUser(CommunityRateLimiter.Policy.PUBLIC_READ_IP, caller.userId());
        } else {
            rateLimiter.admitIp(CommunityRateLimiter.Policy.PUBLIC_READ_IP, caller.remoteAddr());
        }
    }

    /** Trims and clamps to {@value #MAX_QUERY_LENGTH}; blank → null (no filter). */
    private static String boundQuery(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_QUERY_LENGTH ? trimmed : trimmed.substring(0, MAX_QUERY_LENGTH);
    }

    /** Lower-case, trim, de-duplicate, drop blanks (labels are stored lower-case). */
    private static List<String> normalizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : rawTags) {
            if (raw == null) {
                continue;
            }
            String tag = raw.trim().toLowerCase(Locale.ROOT);
            if (!tag.isEmpty()) {
                normalized.add(tag);
            }
        }
        return new ArrayList<>(normalized);
    }
}
