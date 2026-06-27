package com.hhtuann.backend.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Embedded ID for the {@link UserRole} entity.
 * Represents the composite primary key of the {@code user_roles} table.
 */
@Embeddable
public class UserRoleId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "role_id")
    private Long roleId;

    /**
     * Default constructor required by JPA.
     */
    protected UserRoleId() {
    }

    /**
     * Constructor for creating a new composite ID.
     *
     * @param userId the user ID
     * @param roleId the role ID
     */
    public UserRoleId(Long userId, Long roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }

    // ============================================================
    // Getters and Setters
    // ============================================================

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    // ============================================================
    // equals and hashCode (based on both ID fields)
    // ============================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserRoleId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(roleId, that.roleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, roleId);
    }

    // ============================================================
    // toString
    // ============================================================

    @Override
    public String toString() {
        return "UserRoleId{" +
                "userId=" + userId +
                ", roleId=" + roleId +
                '}';
    }
}
