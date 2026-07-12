package com.quizopia.backend.classroom.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code classrooms} table (V10). A classroom is a
 * teacher-owned, school-scoped roster of students that gates CLASS_RESTRICTED
 * exam-session visibility.
 */
@Entity
@Table(name = "classrooms")
public class Classroom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "owner_teacher_id", nullable = false)
    private Long ownerTeacherId;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ClassroomStatus status = ClassroomStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Classroom() {
    }

    public Classroom(Long schoolId, Long ownerTeacherId, String code, String name) {
        this.schoolId = requireNonNullId(schoolId, "schoolId");
        this.ownerTeacherId = requireNonNullId(ownerTeacherId, "ownerTeacherId");
        this.code = requireNonBlank("code", code);
        this.name = requireNonBlank("name", name);
    }

    public Long getId() {
        return id;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public Long getOwnerTeacherId() {
        return ownerTeacherId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ClassroomStatus getStatus() {
        return status;
    }

    public void setStatus(ClassroomStatus status) {
        this.status = status;
    }

    /** Update mutable non-status fields (name, description). */
    public void update(String name, String description) {
        if (name != null) {
            this.name = requireNonBlank("name", name);
        }
        this.description = description;
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
        if (!(o instanceof Classroom other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Classroom.class.hashCode();
    }

    private static Long requireNonNullId(Long value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    private static String requireNonBlank(String fieldName, String value) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
