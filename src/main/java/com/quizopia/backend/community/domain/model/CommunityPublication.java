package com.quizopia.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping {@code community_publications} (ADR-006 §3/§4).
 *
 * <p>One publication per exam ({@code exam_id} UNIQUE, chosen at create and
 * <b>immutable</b> — no setter exists and no code path writes the column
 * after insert; asserted by tests). {@code publisher_user_id} equals
 * {@code exams.created_by_user_id} at every state change — it is never
 * updated, only rechecked (also setter-less).
 *
 * <p>Lifecycle: UNPUBLISHED → (publish) → PUBLISHED → (unpublish) →
 * UNPUBLISHED. Both transitions run under the publication row lock
 * ({@code CommunityPublicationRepository.findByIdForUpdate}) and are
 * idempotent no-ops when already in the target state (except publish from
 * HIDDEN → 409). The {@code version} column is the JPA optimistic token
 * used by the owner PATCH conflict check.
 */
@Entity
@Table(name = "community_publications")
public class CommunityPublication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The published exam this publication exposes (immutable). */
    @Column(name = "exam_id", nullable = false, updatable = false)
    private Long examId;

    /** Owning teacher (immutable; == exams.created_by_user_id). */
    @Column(name = "publisher_user_id", nullable = false, updatable = false)
    private Long publisherUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommunityPublicationStatus status = CommunityPublicationStatus.UNPUBLISHED;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_state", nullable = false, length = 20)
    private CommunityModerationState moderationState = CommunityModerationState.VISIBLE;

    @Enumerated(EnumType.STRING)
    @Column(name = "reuse_policy", nullable = false, length = 20)
    private CommunityReusePolicy reusePolicy = CommunityReusePolicy.NO_FORK;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "summary", length = 500)
    private String summary;

    /** Public credit alias; required at publish (§4/§7). */
    @Column(name = "author_alias", length = 80)
    private String authorAlias;

    @Column(name = "category_id")
    private Long categoryId;

    /** Denormalized from the immutable published composition (§4). */
    @Column(name = "question_count", nullable = false)
    private int questionCount;

    @Column(name = "section_count", nullable = false)
    private int sectionCount;

    /** COALESCE(exams.duration_minutes, 0). */
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** JPA optimistic token (owner PATCH concurrency, §4). */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityPublication() {
    }

    public CommunityPublication(Long examId, Long publisherUserId, String title,
            int questionCount, int sectionCount, int durationMinutes) {
        this.examId = Objects.requireNonNull(examId, "examId must not be null");
        this.publisherUserId = Objects.requireNonNull(publisherUserId, "publisherUserId must not be null");
        this.title = requireTitle(title);
        this.questionCount = questionCount;
        this.sectionCount = sectionCount;
        this.durationMinutes = durationMinutes;
    }

    // ---- state transitions (service calls these under the row lock) ----

    /**
     * UNPUBLISHED → PUBLISHED. Re-derives the denormalized counts from the
     * eligibility recheck and stamps the publish time.
     */
    public void publish(Instant publishedAt, int questionCount, int sectionCount, int durationMinutes) {
        if (status != CommunityPublicationStatus.UNPUBLISHED) {
            throw new IllegalStateException("publish requires UNPUBLISHED state, was " + status);
        }
        this.status = CommunityPublicationStatus.PUBLISHED;
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        this.questionCount = questionCount;
        this.sectionCount = sectionCount;
        this.durationMinutes = durationMinutes;
    }

    /** PUBLISHED → UNPUBLISHED; does not touch exam/session state (§4). */
    public void unpublish() {
        if (status != CommunityPublicationStatus.PUBLISHED) {
            throw new IllegalStateException("unpublish requires PUBLISHED state, was " + status);
        }
        this.status = CommunityPublicationStatus.UNPUBLISHED;
        this.publishedAt = null;
    }

    // ---- PATCHable set (exactly §4: title, summary, categoryId, authorAlias, reusePolicy) ----

    public void rename(String title) {
        this.title = requireTitle(title);
    }

    public void describe(String summary) {
        this.summary = summary;
    }

    public void assignCategory(Long categoryId) {
        this.categoryId = categoryId;
    }

    public void creditAs(String authorAlias) {
        this.authorAlias = authorAlias;
    }

    public void allowReuse(CommunityReusePolicy reusePolicy) {
        this.reusePolicy = Objects.requireNonNull(reusePolicy, "reusePolicy must not be null");
    }

    // ---- reads ----

    public Long getId() {
        return id;
    }

    public Long getExamId() {
        return examId;
    }

    public Long getPublisherUserId() {
        return publisherUserId;
    }

    public CommunityPublicationStatus getStatus() {
        return status;
    }

    public CommunityModerationState getModerationState() {
        return moderationState;
    }

    public CommunityReusePolicy getReusePolicy() {
        return reusePolicy;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getAuthorAlias() {
        return authorAlias;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public int getQuestionCount() {
        return questionCount;
    }

    public int getSectionCount() {
        return sectionCount;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return title.trim();
    }
}
