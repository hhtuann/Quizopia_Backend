package com.hhtuann.backend.identity.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * JPA Entity mapping to the {@code role_permissions} table.
 * Grants fine-grained permissions to roles.
 */
@Entity
@Table(name = "role_permissions")
public class RolePermission {

    @EmbeddedId
    private RolePermissionId id;

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @MapsId("permissionId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id", nullable = false)
    private Permission permission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by")
    private User grantedBy;

    @Column(name = "granted_at", nullable = false)
    @CreationTimestamp
    private Instant grantedAt;

    /**
     * Default constructor required by JPA.
     */
    protected RolePermission() {
    }

    /**
     * Constructor for creating a new role-permission grant.
     *
     * @param role       the role to grant the permission to
     * @param permission the permission to grant
     * @param grantedBy  the user who granted this permission (nullable)
     */
    public RolePermission(Role role, Permission permission, User grantedBy) {
        this.role = role;
        this.permission = permission;
        this.grantedBy = grantedBy;
        this.id = new RolePermissionId(
                role != null ? role.getId() : null,
                permission != null ? permission.getId() : null
        );
    }

    // ============================================================
    // Getters and Setters
    // ============================================================

    public RolePermissionId getId() {
        return id;
    }

    public void setId(RolePermissionId id) {
        this.id = id;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Permission getPermission() {
        return permission;
    }

    public void setPermission(Permission permission) {
        this.permission = permission;
    }

    public User getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(User grantedBy) {
        this.grantedBy = grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(Instant grantedAt) {
        this.grantedAt = grantedAt;
    }

    // ============================================================
    // equals and hashCode (based on embedded ID)
    // ============================================================

    /**
     * Checks if the embedded ID has both non-null components.
     * Used to ensure incomplete IDs are not considered equal.
     *
     * @return true if both roleId and permissionId are non-null
     */
    private boolean hasCompleteId() {
        return id != null
                && id.getRoleId() != null
                && id.getPermissionId() != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof RolePermission other)) {
            return false;
        }

        return hasCompleteId()
                && other.hasCompleteId()
                && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return RolePermission.class.hashCode();
    }

    // ============================================================
    // toString (excludes association fields)
    // ============================================================

    @Override
    public String toString() {
        return "RolePermission{" +
                "id=" + id +
                ", grantedAt=" + grantedAt +
                '}';
    }
}
