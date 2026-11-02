package com.quizopia.backend.exam.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code exams} table. One Exam = one independently
 * identified set of exam content (Task B redesign, issue #2 / ADR-001 §2.4).
 *
 * <p>Lifecycle: DRAFT → PUBLISHED → ARCHIVED. Published content and grading
 * policy are immutable; there is no multi-version content model. The
 * {@code version} column is a JPA optimistic-lock version (NOT content
 * versioning) and doubles as the editor concurrency token.
 *
 * <p>Ownership: {@code workspaceId} + {@code createdByUserUserId}. Legacy
 * {@code schoolId}/{@code subjectId}/{@code ownerTeacherId} remain as nullable
 * provenance.
 */
@Entity
@Table(name = "exams")
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning workspace (NOT NULL). */
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** Creating/owning user (NOT NULL). */
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserUserId;

    /** Legacy provenance; null for exams created outside a school context. */
    @Column(name = "school_id")
    private Long schoolId;

    /** Legacy/academic provenance; optional for personal-workspace exams. */
    @Column(name = "subject_id")
    private Long subjectId;

    /** Legacy provenance (teacher_profiles.id); null for personal exams. */
    @Column(name = "owner_teacher_id")
    private Long ownerTeacherId;

    @Column(name = "purpose_id")
    private Long purposeId;

    @Column(name = "code", nullable = false, length = 80)
    private String code;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description")
    private String description;

    /** Exam-level instructions (moved from legacy exam_versions). */
    @Column(name = "instructions")
    private String instructions;

    /**
     * Default duration (minutes) as editor metadata for pre-filling session
     * forms. Runtime attempt duration uses the SESSION value only.
     * 0 = unlimited convention; null = no default.
     */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    /** Total points of the published composition; 0 while DRAFT. */
    @Column(name = "total_points", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPoints = BigDecimal.ZERO;

    /** Fixed TF-matrix scoring ladder (exact-object JSONB, DB-enforced). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tf_matrix_scoring", columnDefinition = "jsonb")
    private JsonNode tfMatrixScoring = defaultTfMatrixScoring();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ExamStatus status = ExamStatus.DRAFT;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Duplicate provenance: the exam this one was duplicated from, if any. */
    @Column(name = "source_exam_id")
    private Long sourceExamId;

    /**
     * Monotonic composition revision; bumped on every composition replace so
     * the JPA {@code @Version} advances even when settings are unchanged
     * (issue #2: "explicitly increment Exam revision on composition edits").
     */
    @Column(name = "composition_revision", nullable = false)
    private Integer compositionRevision = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Exam() {
    }

    public Exam(Long workspaceId, Long createdByUserUserId, Long schoolId, Long subjectId,
                Long ownerTeacherId, String code, String title) {
        this.workspaceId = ExamPurpose.requireNonNullId(workspaceId, "workspaceId");
        this.createdByUserUserId = ExamPurpose.requireNonNullId(createdByUserUserId, "createdByUserUserId");
        this.schoolId = schoolId;
        this.subjectId = subjectId;
        this.ownerTeacherId = ownerTeacherId;
        this.code = ExamPurpose.requireNonBlank("code", code);
        this.title = ExamPurpose.requireNonBlank("title", title);
    }

    /**
     * Legacy-shape constructor (transitional, ADR-001 §2.2): workspace and
     * creator are resolved by the V11 BEFORE INSERT trigger from the school /
     * owner-teacher provenance. Intended for legacy fixtures and transitional
     * writers; application services must use the explicit constructor.
     */
    public Exam(Long schoolId, Long subjectId, Long ownerTeacherId, String code, String title) {
        this.schoolId = schoolId;
        this.subjectId = subjectId;
        this.ownerTeacherId = ownerTeacherId;
        this.code = ExamPurpose.requireNonBlank("code", code);
        this.title = ExamPurpose.requireNonBlank("title", title);
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getCreatedByUserUserId() {
        return createdByUserUserId;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public Long getOwnerTeacherId() {
        return ownerTeacherId;
    }

    public Long getPurposeId() {
        return purposeId;
    }

    public void setPurposeId(Long purposeId) {
        this.purposeId = purposeId;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getInstructions() {
        return instructions;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public BigDecimal getTotalPoints() {
        return totalPoints;
    }

    public JsonNode getTfMatrixScoring() {
        return tfMatrixScoring;
    }

    public ExamStatus getStatus() {
        return status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Long getSourceExamId() {
        return sourceExamId;
    }

    public Integer getCompositionRevision() {
        return compositionRevision;
    }

    /** Forces a version bump on every composition edit (see field javadoc). */
    public void touchComposition() {
        this.compositionRevision = this.compositionRevision + 1;
    }

    void setSourceExamId(Long sourceExamId) {
        this.sourceExamId = sourceExamId;
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

    public boolean isDraft() {
        return status == ExamStatus.DRAFT;
    }

    /**
     * Updates DRAFT composition settings. Only meaningful while DRAFT.
     */
    public void updateDraftSettings(Integer durationMinutes, String instructions) {
        if (durationMinutes != null) {
            if (durationMinutes < 0) {
                throw new IllegalArgumentException("durationMinutes must be >= 0");
            }
            this.durationMinutes = durationMinutes;
        }
        this.instructions = instructions;
    }

    /**
     * One-way DRAFT → PUBLISHED transition with the published instant and the
     * validated total points (must be > 0; the DB invariant also enforces it).
     */
    public void markPublished(Instant publishedAt, BigDecimal totalPoints) {
        if (this.status != ExamStatus.DRAFT) {
            throw new IllegalStateException("cannot publish exam in status " + status);
        }
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        Objects.requireNonNull(totalPoints, "totalPoints must not be null");
        if (totalPoints.signum() <= 0) {
            throw new IllegalArgumentException("totalPoints must be > 0");
        }
        this.status = ExamStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.totalPoints = totalPoints;
    }

    /**
     * PUBLISHED → ARCHIVED. Callers must first verify no SCHEDULED/OPEN
     * sessions remain (the service enforces that guard).
     */
    public void markArchived() {
        if (this.status != ExamStatus.PUBLISHED) {
            throw new IllegalStateException("cannot archive exam in status " + status);
        }
        this.status = ExamStatus.ARCHIVED;
    }

    /**
     * Copies duplicate provenance onto a freshly created DRAFT clone.
     */
    public void markAsDuplicateOf(Long sourceExamId) {
        this.sourceExamId = Objects.requireNonNull(sourceExamId, "sourceExamId must not be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Exam other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Exam.class.hashCode();
    }

    @Override
    public String toString() {
        return "Exam{" +
                "id=" + id +
                ", workspaceId=" + workspaceId +
                ", createdByUserUserId=" + createdByUserUserId +
                ", schoolId=" + schoolId +
                ", code='" + code + '\'' +
                ", status=" + status +
                ", totalPoints=" + totalPoints +
                ", createdAt=" + createdAt +
                '}';
    }

    /**
     * Fresh, non-shared JsonNode with the exact shared TF-MATRIX scoring ladder.
     */
    private static JsonNode defaultTfMatrixScoring() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("0", 0);
        node.put("1", 10);
        node.put("2", 25);
        node.put("3", 50);
        node.put("4", 100);
        return node;
    }
}
