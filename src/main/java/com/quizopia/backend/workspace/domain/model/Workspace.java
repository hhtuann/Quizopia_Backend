package com.quizopia.backend.workspace.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code workspaces} table (ADR-001 §2.1). A workspace
 * is the ownership container for question banks, exams and classrooms.
 *
 * <p>Shape invariant (DB CHECK {@code chk_workspaces_shape}): a PERSONAL
 * workspace has exactly one owner user and no organization; an ORGANIZATION
 * workspace references exactly one organization and has no direct owner.
 */
@Entity
@Table(name = "workspaces")
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "workspace_type", nullable = false, length = 20)
    private WorkspaceType workspaceType;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Owner user; NOT NULL for PERSONAL, NULL for ORGANIZATION. */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** Referenced organization; NOT NULL for ORGANIZATION, NULL for PERSONAL. */
    @Column(name = "organization_id")
    private Long organizationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Workspace() {
    }

    public static Workspace personal(Long ownerUserId, String name) {
        Workspace workspace = new Workspace();
        workspace.workspaceType = WorkspaceType.PERSONAL;
        workspace.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        workspace.organizationId = null;
        workspace.name = requireNonBlank(name);
        return workspace;
    }

    public static Workspace organizational(Long organizationId, String name) {
        Workspace workspace = new Workspace();
        workspace.workspaceType = WorkspaceType.ORGANIZATION;
        workspace.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        workspace.ownerUserId = null;
        workspace.name = requireNonBlank(name);
        return workspace;
    }

    public Long getId() {
        return id;
    }

    public WorkspaceType getWorkspaceType() {
        return workspaceType;
    }

    public String getName() {
        return name;
    }

    public void rename(String name) {
        this.name = requireNonBlank(name);
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public boolean isPersonal() {
        return workspaceType == WorkspaceType.PERSONAL;
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
        if (!(o instanceof Workspace other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Workspace.class.hashCode();
    }

    @Override
    public String toString() {
        return "Workspace{" +
                "id=" + id +
                ", workspaceType=" + workspaceType +
                ", name='" + name + '\'' +
                ", ownerUserId=" + ownerUserId +
                ", organizationId=" + organizationId +
                '}';
    }

    private static String requireNonBlank(String value) {
        Objects.requireNonNull(value, "name must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return value;
    }
}
