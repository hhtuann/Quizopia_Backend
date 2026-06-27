package com.hhtuann.backend.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Embedded ID for the {@link RolePermission} entity.
 * Represents the composite primary key of the {@code role_permissions} table.
 */
@Embeddable
public class RolePermissionId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "permission_id")
    private Long permissionId;

    /**
     * Default constructor required by JPA.
     */
    protected RolePermissionId() {
    }

    /**
     * Constructor for creating a new composite ID.
     *
     * @param roleId       the role ID
     * @param permissionId the permission ID
     */
    public RolePermissionId(Long roleId, Long permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    // ============================================================
    // Getters and Setters
    // ============================================================

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public Long getPermissionId() {
        return permissionId;
    }

    public void setPermissionId(Long permissionId) {
        this.permissionId = permissionId;
    }

    // ============================================================
    // equals and hashCode (based on both ID fields)
    // ============================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RolePermissionId that)) return false;
        return Objects.equals(roleId, that.roleId) && Objects.equals(permissionId, that.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, permissionId);
    }

    // ============================================================
    // toString
    // ============================================================

    @Override
    public String toString() {
        return "RolePermissionId{" +
                "roleId=" + roleId +
                ", permissionId=" + permissionId +
                '}';
    }
}
