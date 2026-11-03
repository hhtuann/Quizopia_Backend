package com.quizopia.backend.workspace.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code workspace_memberships} table (ADR-001 §2.1).
 * Membership expresses workspace-level role and lifecycle for a user; it is
 * the authorization boundary for workspace-scoped resources and is distinct
 * from classroom membership.
 */
@Entity
@Table(name = "workspace_memberships")
public class WorkspaceMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private WorkspaceRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkspaceMembershipStatus status = WorkspaceMembershipStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkspaceMembership() {
    }

    public WorkspaceMembership(Long workspaceId, Long userId, WorkspaceRole role) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.status = WorkspaceMembershipStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getUserId() {
        return userId;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public WorkspaceMembershipStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == WorkspaceMembershipStatus.ACTIVE;
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
        if (!(o instanceof WorkspaceMembership other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return WorkspaceMembership.class.hashCode();
    }

    @Override
    public String toString() {
        return "WorkspaceMembership{" +
                "id=" + id +
                ", workspaceId=" + workspaceId +
                ", userId=" + userId +
                ", role=" + role +
                ", status=" + status +
                '}';
    }
}
