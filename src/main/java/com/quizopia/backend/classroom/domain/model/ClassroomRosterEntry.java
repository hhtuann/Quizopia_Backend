package com.quizopia.backend.classroom.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * JPA entity mapping to {@code classroom_roster_entries} (Identity-M3):
 * a teacher's pending invitation for a learner identified by EMAIL — the
 * matching address, never a user identity key. Sensitive fields (DOB,
 * phones, the raw email) are stored only as AES-256-GCM ciphertext
 * (v1: prefix), mirroring {@code users.phone_encrypted}.
 *
 * <p>Lifecycle: PENDING → LINKED (the email's verified owner accepted via
 * automatic reconciliation) / REVOKED (teacher withdrew) / DECLINED (the
 * learner declined after being linked — membership removed, entry kept for
 * audit).
 */
@Entity
@Table(name = "classroom_roster_entries", indexes = {
        @Index(name = "idx_roster_entries_classroom", columnList = "classroom_id, status")
})
public class ClassroomRosterEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "classroom_id", nullable = false)
    private Classroom classroom;

    /** Read-only mirror of the FK for lazy-safe access outside a session. */
    @Column(name = "classroom_id", insertable = false, updatable = false)
    private Long classroomId;

    /** Canonical matching address: trimmed + lowercased. */
    @Column(name = "email_normalized", nullable = false, length = 254)
    private String emailNormalized;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RosterStatus status = RosterStatus.PENDING;

    /** Set at reconciliation; kept on DECLINED for audit. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "student_number", length = 50)
    private String studentNumber;

    /** MALE / FEMALE / OTHER or null. */
    @Column(name = "gender", length = 10)
    private String gender;

    /** AES-256-GCM ciphertext (v1:) of the date of birth — never plaintext. */
    @Column(name = "dob_encrypted", columnDefinition = "text")
    private String dobEncrypted;

    /** AES-256-GCM ciphertext (v1:) of the learner's phone. */
    @Column(name = "phone_encrypted", columnDefinition = "text")
    private String phoneEncrypted;

    /** AES-256-GCM ciphertext (v1:) of the parent's phone. */
    @Column(name = "parent_phone_encrypted", columnDefinition = "text")
    private String parentPhoneEncrypted;

    /** AES-256-GCM ciphertext (v1:) of the raw (non-canonicalized) email. */
    @Column(name = "email_encrypted", columnDefinition = "text")
    private String emailEncrypted;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "linked_at")
    private Instant linkedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ClassroomRosterEntry() {
    }

    public ClassroomRosterEntry(Classroom classroom, String emailNormalized,
            String fullName, Long createdByUserId) {
        this.classroom = classroom;
        this.emailNormalized = emailNormalized;
        this.fullName = fullName;
        this.createdByUserId = createdByUserId;
    }

    public void linkTo(Long userId, Instant at) {
        this.status = RosterStatus.LINKED;
        this.userId = userId;
        this.linkedAt = at;
    }

    public void revoke(Instant at) {
        this.status = RosterStatus.REVOKED;
        this.revokedAt = at;
        this.userId = null;
    }

    public void decline(Instant at) {
        this.status = RosterStatus.DECLINED;
        this.leftAt = at;
        // user_id intentionally KEPT for audit (constraint allows it).
    }

    /**
     * CLASS-M1 (issue #50): synthetic opt-out record for a learner who
     * LEFT a direct-add membership (no roster row ever existed). Records
     * the standing DECLINED history that keeps a future invitation of the
     * same (classroom, email) from auto-relinking without explicit
     * learner consent — same guard as a learner-declined LINKED entry.
     */
    public static ClassroomRosterEntry declinedOptOut(Classroom classroom,
            String emailNormalized, String fullName, Long userId, Instant at) {
        ClassroomRosterEntry entry = new ClassroomRosterEntry(
                classroom, emailNormalized, fullName, userId);
        entry.status = RosterStatus.DECLINED;
        entry.userId = userId;
        entry.leftAt = at;
        return entry;
    }

    public Long getId() { return id; }
    public Classroom getClassroom() { return classroom; }
    public Long getClassroomId() { return classroomId; }
    public String getEmailNormalized() { return emailNormalized; }
    public RosterStatus getStatus() { return status; }
    public Long getUserId() { return userId; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getStudentNumber() { return studentNumber; }
    public void setStudentNumber(String studentNumber) { this.studentNumber = studentNumber; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getDobEncrypted() { return dobEncrypted; }
    public void setDobEncrypted(String dobEncrypted) { this.dobEncrypted = dobEncrypted; }
    public String getPhoneEncrypted() { return phoneEncrypted; }
    public void setPhoneEncrypted(String phoneEncrypted) { this.phoneEncrypted = phoneEncrypted; }
    public String getParentPhoneEncrypted() { return parentPhoneEncrypted; }
    public void setParentPhoneEncrypted(String parentPhoneEncrypted) { this.parentPhoneEncrypted = parentPhoneEncrypted; }
    public String getEmailEncrypted() { return emailEncrypted; }
    public void setEmailEncrypted(String emailEncrypted) { this.emailEncrypted = emailEncrypted; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLinkedAt() { return linkedAt; }
    public Instant getLeftAt() { return leftAt; }
    public Instant getRevokedAt() { return revokedAt; }

    @Override
    public String toString() {
        // Review M3-FINAL-04: identifiers and state ONLY — no names, no
        // emails (even canonical), no DOB/phones, no ciphertext.
        return "ClassroomRosterEntry{id=" + id
                + ", classroomId=" + classroomId
                + ", status=" + status
                + ", linkedAt=" + linkedAt
                + ", createdAt=" + createdAt + '}';
    }

    /** Explicit roster lifecycle (issue #15). */
    public enum RosterStatus { PENDING, LINKED, REVOKED, DECLINED }
}
