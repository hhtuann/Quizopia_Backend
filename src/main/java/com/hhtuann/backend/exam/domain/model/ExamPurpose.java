package com.hhtuann.backend.exam.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code exam_purposes} table. An exam purpose is a
 * school-scoped catalog entry (e.g. MIDTERM, FINAL). TEACHER has read-only
 * access; purposes are seeded by the V8 migration and DemoDataSeeder.
 */
@Entity
@Table(name = "exam_purposes")
public class ExamPurpose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "position", nullable = false)
    private Integer position = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExamPurpose() {
    }

    public ExamPurpose(Long schoolId, String code, String title) {
        this.schoolId = requireNonNullId(schoolId, "schoolId");
        this.code = requireNonBlank("code", code);
        this.title = requireNonBlank("title", title);
    }

    public Long getId() {
        return id;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExamPurpose other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return ExamPurpose.class.hashCode();
    }

    @Override
    public String toString() {
        return "ExamPurpose{" +
                "id=" + id +
                ", schoolId=" + schoolId +
                ", code='" + code + '\'' +
                ", position=" + position +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }

    // -- Package-private validation helpers (shared by all exam entities) --

    static Long requireNonNullId(Long value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    static String requireNonBlank(String fieldName, String value) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
