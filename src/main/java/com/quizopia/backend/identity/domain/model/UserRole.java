package com.quizopia.backend.identity.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * JPA Entity mapping to the {@code user_roles} table.
 * Assigns one or more roles to a user.
 */
@Entity
@Table(name = "user_roles")
public class UserRole {

    @EmbeddedId
    private UserRoleId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private User assignedBy;

    @Column(name = "assigned_at", nullable = false)
    @CreationTimestamp
    private Instant assignedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Default constructor required by JPA.
     */
    protected UserRole() {
    }

    /**
     * Constructor for creating a new user-role assignment.
     *
     * @param user       the user to assign the role to
     * @param role       the role to assign
     * @param assignedBy the user who made this assignment (nullable)
     * @param expiresAt  optional expiration time
     */
    public UserRole(User user, Role role, User assignedBy, Instant expiresAt) {
        this.user = user;
        this.role = role;
        this.assignedBy = assignedBy;
        this.expiresAt = expiresAt;
        this.id = new UserRoleId(
                user != null ? user.getId() : null,
                role != null ? role.getId() : null
        );
    }

    // ============================================================
    // Getters and Setters
    // ============================================================

    public UserRoleId getId() {
        return id;
    }

    public void setId(UserRoleId id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public User getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(User assignedBy) {
        this.assignedBy = assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    // ============================================================
    // equals and hashCode (based on embedded ID)
    // ============================================================

    /**
     * Checks if the embedded ID has both non-null components.
     * Used to ensure incomplete IDs are not considered equal.
     *
     * @return true if both userId and roleId are non-null
     */
    private boolean hasCompleteId() {
        return id != null
                && id.getUserId() != null
                && id.getRoleId() != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof UserRole other)) {
            return false;
        }

        return hasCompleteId()
                && other.hasCompleteId()
                && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return UserRole.class.hashCode();
    }

    // ============================================================
    // toString (excludes association fields)
    // ============================================================

    @Override
    public String toString() {
        return "UserRole{" +
                "id=" + id +
                ", assignedAt=" + assignedAt +
                ", expiresAt=" + expiresAt +
                '}';
    }
}
