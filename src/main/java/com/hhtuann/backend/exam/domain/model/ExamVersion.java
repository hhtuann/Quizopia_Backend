package com.hhtuann.backend.exam.domain.model;

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
 * JPA entity mapping the {@code exam_versions} table. A version holds either a
 * DRAFT composition (editable) or a PUBLISHED immutable snapshot.
 *
 * <p>No {@code @Version} column — the table has no optimistic-lock version.
 * The {@code tf_matrix_scoring} JSONB is initialised per-instance with the exact
 * shared TF-MATRIX scoring ladder (never a shared mutable JsonNode).
 * Published invariant: DRAFT → publishedAt null; PUBLISHED → publishedAt not
 * null AND totalPoints > 0.
 */
@Entity
@Table(name = "exam_versions")
public class ExamVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "instructions")
    private String instructions;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes = 60;

    @Column(name = "total_points", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPoints = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tf_matrix_scoring", nullable = false, columnDefinition = "jsonb")
    private JsonNode tfMatrixScoring = defaultTfMatrixScoring();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ExamVersionStatus status = ExamVersionStatus.DRAFT;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExamVersion() {
    }

    public ExamVersion(Long schoolId, Long examId, Integer versionNumber, Long createdBy) {
        this.schoolId = ExamPurpose.requireNonNullId(schoolId, "schoolId");
        this.examId = ExamPurpose.requireNonNullId(examId, "examId");
        this.versionNumber = requirePositiveVersion(versionNumber);
        this.createdBy = ExamPurpose.requireNonNullId(createdBy, "createdBy");
    }

    public Long getId() {
        return id;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public Long getExamId() {
        return examId;
    }

    public Integer getVersionNumber() {
        return versionNumber;
    }

    public String getTitle() {
        return title;
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

    public ExamVersionStatus getStatus() {
        return status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Updates DRAFT composition settings (duration, instructions, title snapshot).
     * Only meaningful while the version is in DRAFT state.
     */
    public void updateDraftSettings(Integer durationMinutes, String instructions, String title) {
        if (durationMinutes != null) {
            if (durationMinutes <= 0) {
                throw new IllegalArgumentException("durationMinutes must be > 0");
            }
            this.durationMinutes = durationMinutes;
        }
        this.instructions = instructions;
        this.title = title;
    }

    /**
     * Marks this version as PUBLISHED with the given timestamp and total points.
     * Validates totalPoints > 0 (the DB invariant CHECK also enforces this).
     */
    public void markPublished(Instant publishedAt, BigDecimal totalPoints) {
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        Objects.requireNonNull(totalPoints, "totalPoints must not be null");
        if (totalPoints.signum() <= 0) {
            throw new IllegalArgumentException("totalPoints must be > 0");
        }
        this.status = ExamVersionStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.totalPoints = totalPoints;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExamVersion other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return ExamVersion.class.hashCode();
    }

    @Override
    public String toString() {
        return "ExamVersion{" +
                "id=" + id +
                ", examId=" + examId +
                ", versionNumber=" + versionNumber +
                ", durationMinutes=" + durationMinutes +
                ", totalPoints=" + totalPoints +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }

    /**
     * Builds a fresh, non-shared JsonNode for the exact TF_MATRIX scoring ladder.
     * Each ExamVersion instance gets its own mutable object (no shared state).
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

    private static Integer requirePositiveVersion(Integer versionNumber) {
        Objects.requireNonNull(versionNumber, "versionNumber must not be null");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be >= 1");
        }
        return versionNumber;
    }
}
