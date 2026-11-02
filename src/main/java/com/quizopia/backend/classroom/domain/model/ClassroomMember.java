package com.quizopia.backend.classroom.domain.model;

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
 * JPA entity mapping the {@code classroom_members} table. User-based
 * membership (V12, issue #1 A4): {@code userId} is the member identity;
 * the legacy {@code studentProfileId}/{@code schoolId} columns remain as
 * nullable provenance for school-era rows. Membership is the sole
 * gatekeeper for CLASS_RESTRICTED exam-session visibility.
 */
@Entity
@Table(name = "classroom_members")
public class ClassroomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "classroom_id", nullable = false)
    private Long classroomId;

    /** Member user (NOT NULL, V12). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Legacy provenance; null for members added after the user-based cutover. */
    @Column(name = "student_profile_id")
    private Long studentProfileId;

    /** Legacy provenance; null for members added after the user-based cutover. */
    @Column(name = "school_id")
    private Long schoolId;

    @CreationTimestamp
    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    protected ClassroomMember() {
    }

    public ClassroomMember(Long classroomId, Long userId, Long studentProfileId, Long schoolId) {
        this.classroomId = Objects.requireNonNull(classroomId, "classroomId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.studentProfileId = studentProfileId;
        this.schoolId = schoolId;
    }

    public Long getId() {
        return id;
    }

    public Long getClassroomId() {
        return classroomId;
    }

    public Long getUserId() {
        return userId;
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

    @Override
    public String toString() {
        return "ClassroomMember{" +
                "id=" + id +
                ", classroomId=" + classroomId +
                ", userId=" + userId +
                ", studentProfileId=" + studentProfileId +
                ", schoolId=" + schoolId +
                ", addedAt=" + addedAt +
                '}';
    }
}
