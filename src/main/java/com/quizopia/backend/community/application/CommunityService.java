package com.quizopia.backend.community.application;

import com.quizopia.backend.community.domain.model.CommunityModerationState;
import com.quizopia.backend.community.domain.model.CommunityPublication;
import com.quizopia.backend.community.domain.model.CommunityPublicationStatus;
import com.quizopia.backend.community.domain.model.CommunityPublicationTag;
import com.quizopia.backend.community.domain.model.CommunityReportStatus;
import com.quizopia.backend.community.domain.model.CommunityReusePolicy;
import com.quizopia.backend.community.dto.CreatePublicationRequest;
import com.quizopia.backend.community.dto.OwnerPublicationResponse;
import com.quizopia.backend.community.dto.UpdatePublicationRequest;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.community.repository.CommunityCategoryRepository;
import com.quizopia.backend.community.repository.CommunityPublicationRepository;
import com.quizopia.backend.community.repository.CommunityPublicationTagRepository;
import com.quizopia.backend.community.repository.CommunityRateBucketRepository;
import com.quizopia.backend.community.repository.CommunityReportRepository;
import com.quizopia.backend.community.repository.CommunityTagRepository;
import com.quizopia.backend.exam.application.ExamCommunityPort;
import com.quizopia.backend.exam.domain.model.ExamStatus;
import com.quizopia.backend.question.dto.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Owner lifecycle service for community publications (ADR-006 §4, issue
 * #40): create / PATCH / publish / unpublish / list-own.
 *
 * <p>Lock discipline (§4/§8): publish and unpublish take ONLY the
 * publication row lock ({@code findByIdForUpdate}); PATCH relies on the
 * JPA {@code @Version} optimistic token ({@code expectedVersion}); the
 * exam row is never locked by community code. Rate admission is the
 * first statement of every write transaction (§9) — before any
 * authorization read or lock.
 *
 * <p>Anti-enumeration: absent, foreign and ineligible-exam resources are
 * 404 (COMMUNITY_NOT_FOUND); "authenticated but not allowed" is 403
 * (COMMUNITY_ACCESS_DENIED).
 */
@Service
public class CommunityService {

    static final String PERMISSION_PUBLISH = "COMMUNITY_PUBLISH";
    static final int MAX_TAGS = 5;
    private static final Pattern TAG_PATTERN = Pattern.compile("[a-z0-9][a-z0-9 -]{0,39}");
    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> SORT_ALLOWLIST = Set.of("createdAt", "publishedAt", "title");

    private final CommunityPublicationRepository publications;
    private final CommunityPublicationTagRepository publicationTags;
    private final CommunityTagRepository tags;
    private final CommunityCategoryRepository categories;
    private final CommunityReportRepository reports;
    private final CommunityAuthorizationService auth;
    private final CommunityRateLimiter rateLimiter;
    private final ExamCommunityPort examCommunityPort;
    private final Clock clock;

    public CommunityService(CommunityPublicationRepository publications,
            CommunityPublicationTagRepository publicationTags,
            CommunityTagRepository tags,
            CommunityCategoryRepository categories,
            CommunityReportRepository reports,
            CommunityAuthorizationService auth,
            CommunityRateLimiter rateLimiter,
            ExamCommunityPort examCommunityPort,
            Clock clock) {
        this.publications = publications;
        this.publicationTags = publicationTags;
        this.tags = tags;
        this.categories = categories;
        this.reports = reports;
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.examCommunityPort = examCommunityPort;
        this.clock = clock;
    }

    // ============================================================
    // POST /api/community/publications
    // ============================================================

    @Transactional
    public OwnerPublicationResponse createPublication(Long userId, CreatePublicationRequest request) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.CREATE_UPDATE, userId);

        CommunityAuthorizationService.PublisherAccount account =
                auth.requireEligiblePublisher(userId, PERMISSION_PUBLISH);

        String title = requireTitle(request.title());
        String summary = trimmedOrNull(request.summary());
        String authorAlias = trimmedOrNull(request.authorAlias());
        validateAlias(authorAlias, account);
        CommunityReusePolicy reusePolicy = parseReusePolicy(request.reusePolicy());
        Set<String> normalizedTags = normalizeTags(request.tags());
        requireExistingCategory(request.categoryId());

        if (publications.existsByExamId(request.examId())) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_PUBLICATION_EXISTS);
        }

        // Eligibility (§4): exam exists, owned by caller, PUBLISHED. A
        // foreign or missing exam is indistinguishable (404).
        ExamCommunityPort.PublicationEligibility eligibility = examCommunityPort
                .checkPublicationEligibility(request.examId(), userId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));
        if (eligibility.examStatus() != ExamStatus.PUBLISHED) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_PUBLISH_INELIGIBLE);
        }

        CommunityPublication publication = new CommunityPublication(
                request.examId(), userId, title,
                eligibility.questionCount(), eligibility.sectionCount(), eligibility.durationMinutes());
        publication.describe(summary);
        publication.assignCategory(request.categoryId());
        publication.creditAs(authorAlias);
        publication.allowReuse(reusePolicy);
        try {
            publication = publications.saveAndFlush(publication);
        } catch (DataIntegrityViolationException ex) {
            // UNIQUE(exam_id): a concurrent create for the same exam won.
            throw new CommunityException(CommunityErrorCode.COMMUNITY_PUBLICATION_EXISTS);
        }

        replaceTags(publication.getId(), normalizedTags);
        return ownerResponse(publication);
    }

    // ============================================================
    // PATCH /api/community/publications/{id}
    // ============================================================

    @Transactional
    public OwnerPublicationResponse updatePublication(Long userId, Long publicationId,
            UpdatePublicationRequest request) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.CREATE_UPDATE, userId);
        auth.requireTeacherWithPermission(userId, PERMISSION_PUBLISH);

        CommunityPublication publication = requireOwnedPublication(userId, publicationId);

        if (!publication.getVersion().equals(request.expectedVersion())) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_CONCURRENT_MODIFICATION);
        }

        // PATCHable set exactly {title, summary, categoryId, tags, authorAlias,
        // reusePolicy}; null = unchanged. examId/publisher have no setter at all.
        if (request.title() != null) {
            publication.rename(requireTitle(request.title()));
        }
        if (request.summary() != null) {
            publication.describe(trimmedOrNull(request.summary()));
        }
        if (request.categoryId() != null) {
            requireExistingCategory(request.categoryId());
            publication.assignCategory(request.categoryId());
        }
        if (request.authorAlias() != null) {
            String alias = trimmedOrNull(request.authorAlias());
            validateAlias(alias, auth.account(userId));
            publication.creditAs(alias);
        }
        if (request.reusePolicy() != null) {
            publication.allowReuse(parseReusePolicy(request.reusePolicy()));
        }

        try {
            publications.saveAndFlush(publication);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_CONCURRENT_MODIFICATION);
        }
        if (request.tags() != null) {
            replaceTags(publication.getId(), normalizeTags(request.tags()));
        }
        return ownerResponse(publications.findById(publication.getId()).orElseThrow());
    }

    // ============================================================
    // POST /api/community/publications/{id}/publish
    // ============================================================

    @Transactional
    public OwnerPublicationResponse publishPublication(Long userId, Long publicationId) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userId);
        auth.requireTeacherWithPermission(userId, PERMISSION_PUBLISH);

        CommunityPublication publication = requireOwnedPublicationForUpdate(userId, publicationId);

        if (publication.getModerationState() == CommunityModerationState.HIDDEN) {
            // The author cannot bypass a moderator hide (§4); the hide
            // survives unpublish/republish.
            throw new CommunityException(CommunityErrorCode.COMMUNITY_MODERATION_HIDDEN);
        }
        if (publication.getStatus() == CommunityPublicationStatus.PUBLISHED) {
            // Idempotent no-op: no version bump, no published_at change.
            return ownerResponse(publication);
        }

        String alias = publication.getAuthorAlias();
        if (alias == null || alias.isBlank()) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ALIAS_REQUIRED);
        }
        validateAlias(alias, auth.account(userId));

        // Eligibility recheck under the publication row lock (§4): the exam
        // must still exist and be PUBLISHED, and the publisher must still be
        // ACTIVE + verified + TEACHER + COMMUNITY_PUBLISH. Any failure is
        // 409 COMMUNITY_PUBLISH_INELIGIBLE (the caller passed the route
        // gate; the state changed since create).
        ExamCommunityPort.PublicationEligibility eligibility = examCommunityPort
                .checkPublicationEligibility(publication.getExamId(), publication.getPublisherUserId())
                .filter(e -> e.examStatus() == ExamStatus.PUBLISHED)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_PUBLISH_INELIGIBLE));
        if (!auth.isEligiblePublisher(publication.getPublisherUserId(), PERMISSION_PUBLISH)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_PUBLISH_INELIGIBLE);
        }

        publication.publish(Instant.now(clock),
                eligibility.questionCount(), eligibility.sectionCount(), eligibility.durationMinutes());
        publications.saveAndFlush(publication);
        return ownerResponse(publication);
    }

    // ============================================================
    // POST /api/community/publications/{id}/unpublish
    // ============================================================

    @Transactional
    public OwnerPublicationResponse unpublishPublication(Long userId, Long publicationId) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userId);
        auth.requireTeacherWithPermission(userId, PERMISSION_PUBLISH);

        CommunityPublication publication = requireOwnedPublicationForUpdate(userId, publicationId);

        if (publication.getStatus() == CommunityPublicationStatus.UNPUBLISHED) {
            // Idempotent no-op (§4); HIDDEN does not block unpublish —
            // the hide survives and blocks the next publish instead.
            return ownerResponse(publication);
        }

        publication.unpublish();
        publications.saveAndFlush(publication);
        return ownerResponse(publication);
    }

    // ============================================================
    // GET /api/community/me/publications
    // ============================================================

    @Transactional(readOnly = true)
    public PageResponse<OwnerPublicationResponse> listMyPublications(
            Long userId, int page, int size, String sort) {
        auth.requireTeacherWithPermission(userId, PERMISSION_PUBLISH);

        PageRequest pageable = safePageable(page, size, sort);
        Page<CommunityPublication> result = publications.findByPublisherUserId(userId, pageable);

        List<CommunityPublication> content = result.getContent();
        Map<Long, List<String>> tagsByPublication = loadTags(
                content.stream().map(CommunityPublication::getId).toList());
        Map<Long, Long> openCounts = openReportCounts(
                content.stream().map(CommunityPublication::getId).toList());
        return new PageResponse<>(
                content.stream()
                        .map(p -> ownerResponse(p,
                                tagsByPublication.getOrDefault(p.getId(), List.of()),
                                openCounts.getOrDefault(p.getId(), 0L)))
                        .toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                pageable.getSort().toString());
    }

    /** OPEN reports for one publication (§7 owner DTO aggregate). */
    private long openReportCount(long publicationId) {
        return reports.countByPublicationIdAndStatus(publicationId, CommunityReportStatus.OPEN);
    }

    /** Batched OPEN report counts for the owner list (single query). */
    private Map<Long, Long> openReportCounts(List<Long> publicationIds) {
        if (publicationIds.isEmpty()) {
            return Map.of();
        }
        return reports.countByPublicationIdsAndStatus(publicationIds, CommunityReportStatus.OPEN)
                .stream().collect(Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> ((Number) row[1]).longValue()));
    }

    // ============================================================
    // helpers
    // ============================================================

    /** Absent OR foreign → 404 (anti-enumeration); optimistic (no lock). */
    private CommunityPublication requireOwnedPublication(Long userId, Long publicationId) {
        CommunityPublication publication = publications.findById(publicationId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));
        if (!publication.getPublisherUserId().equals(userId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND);
        }
        return publication;
    }

    /** Same as {@link #requireOwnedPublication} under the row lock. */
    private CommunityPublication requireOwnedPublicationForUpdate(Long userId, Long publicationId) {
        CommunityPublication publication = publications.findByIdForUpdate(publicationId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));
        if (!publication.getPublisherUserId().equals(userId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND);
        }
        return publication;
    }

    /** Replace-set semantics: delete all links, upsert labels, relink. */
    private void replaceTags(Long publicationId, Set<String> normalizedTags) {
        publicationTags.deleteByPublicationId(publicationId);
        if (normalizedTags.isEmpty()) {
            publicationTags.flush();
            return;
        }
        for (String label : normalizedTags) {
            tags.insertIfAbsent(label);
        }
        List<Long> tagIds = tags.findByLabelIn(normalizedTags).stream()
                .map(t -> t.getId())
                .toList();
        for (Long tagId : tagIds) {
            publicationTags.save(new CommunityPublicationTag(publicationId, tagId));
        }
        publicationTags.flush();
    }

    private Map<Long, List<String>> loadTags(List<Long> publicationIds) {
        if (publicationIds.isEmpty()) {
            return Map.of();
        }
        return publicationTags.findLabelsByPublicationIds(publicationIds).stream()
                .collect(Collectors.groupingBy(
                        row -> ((Number) row[0]).longValue(),
                        Collectors.mapping(row -> (String) row[1], Collectors.toList())));
    }

    private OwnerPublicationResponse ownerResponse(CommunityPublication publication) {
        return ownerResponse(publication,
                loadTags(List.of(publication.getId())).getOrDefault(publication.getId(), List.of()),
                openReportCount(publication.getId()));
    }

    private OwnerPublicationResponse ownerResponse(CommunityPublication publication,
            List<String> tagLabels, long openReportCount) {
        String categoryCode = null;
        String categoryTitle = null;
        if (publication.getCategoryId() != null) {
            var category = categories.findById(publication.getCategoryId()).orElse(null);
            if (category != null) {
                categoryCode = category.getCode();
                categoryTitle = category.getTitle();
            }
        }
        return new OwnerPublicationResponse(
                publication.getId(),
                publication.getExamId(),
                publication.getTitle(),
                publication.getSummary(),
                publication.getAuthorAlias(),
                publication.getCategoryId(),
                categoryCode,
                categoryTitle,
                tagLabels,
                publication.getStatus().name(),
                publication.getModerationState().name(),
                publication.getReusePolicy().name(),
                publication.getQuestionCount(),
                publication.getSectionCount(),
                publication.getDurationMinutes(),
                publication.getPublishedAt(),
                publication.getCreatedAt(),
                publication.getUpdatedAt(),
                publication.getVersion(),
                openReportCount);
    }

    private void requireExistingCategory(Long categoryId) {
        if (categoryId != null && !categories.existsById(categoryId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_CATEGORY_NOT_FOUND);
        }
    }

    /** §7: the public alias must not equal the account email or username (ci). */
    private void validateAlias(String alias, CommunityAuthorizationService.PublisherAccount account) {
        if (alias == null) {
            return;
        }
        if (alias.equalsIgnoreCase(account.email()) || alias.equalsIgnoreCase(account.username())) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ALIAS_INVALID);
        }
    }

    private Set<String> normalizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : rawTags) {
            if (raw == null) {
                throw new CommunityException(CommunityErrorCode.COMMUNITY_TAG_INVALID, "Tag must not be null");
            }
            String tag = raw.trim().toLowerCase(Locale.ROOT);
            if (!TAG_PATTERN.matcher(tag).matches()) {
                throw new CommunityException(CommunityErrorCode.COMMUNITY_TAG_INVALID,
                        "Tag '" + tag + "' does not match [a-z0-9][a-z0-9 -]{0,39}");
            }
            normalized.add(tag);
        }
        if (normalized.size() > MAX_TAGS) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_TAG_INVALID,
                    "At most " + MAX_TAGS + " distinct tags are allowed");
        }
        return normalized;
    }

    private CommunityReusePolicy parseReusePolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return CommunityReusePolicy.NO_FORK;
        }
        try {
            return CommunityReusePolicy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "reusePolicy must be NO_FORK or ALLOW_FORK");
        }
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR, "title must not be blank");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 255) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR, "title is too long");
        }
        return trimmed;
    }

    private static String trimmedOrNull(String value) {
        return value == null ? null : value.trim();
    }

    private PageRequest safePageable(int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Sort safeSort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String prop = parts[0].trim();
            Sort.Direction dir = (parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc"))
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;
            if (!SORT_ALLOWLIST.contains(prop)) {
                throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                        "sort must be one of " + SORT_ALLOWLIST);
            }
            safeSort = Sort.by(dir, prop).and(Sort.by(Sort.Direction.DESC, "id"));
        } else {
            safeSort = safeSort.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        return PageRequest.of(safePage, safeSize, safeSort);
    }
}
