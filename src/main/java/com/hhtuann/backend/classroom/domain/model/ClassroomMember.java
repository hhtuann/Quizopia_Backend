package com.hhtuann.backend.classroom.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code classroom_members} table (V10). Membership is
 * the sole gatekeeper for CLASS_RESTRICTED exam-session visibility (replaces the
 * legacy exam_session_participants ELIGIBLE/BLOCKED model).
 */
@Entity
@Table(name = "classroom_members")
public class ClassroomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "classroom_id", nullable = false)
    private Long classroomId;

    @Column(name = "student_profile_id", nullable = false)
    private Long studentProfileId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @CreationTimestamp
    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    protected ClassroomMember() {
    }

    public ClassroomMember(Long classroomId, Long studentProfileId, Long schoolId) {
        this.classroomId = Objects.requireNonNull(classroomId, "classroomId must not be null");
        this.studentProfileId = Objects.requireNonNull(studentProfileId, "studentProfileId must not be null");
        this.schoolId = Objects.requireNonNull(schoolId, "schoolId must not be null");
    }

    public Long getId() {
        return id;
    }

    public Long getClassroomId() {
        return classroomId;
    }

    public Long getStudentProfileId() {
        return studentProfileId;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClassroomMember other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return ClassroomMember.class.hashCode();
    }
}
