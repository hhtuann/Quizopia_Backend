package com.quizopia.backend.workspace.repository;

import com.quizopia.backend.workspace.domain.model.Workspace;
import com.quizopia.backend.workspace.domain.model.WorkspaceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    Optional<Workspace> findByOwnerUserIdAndWorkspaceType(Long ownerUserId, WorkspaceType workspaceType);

    Optional<Workspace> findByOrganizationIdAndWorkspaceType(Long organizationId, WorkspaceType workspaceType);

    boolean existsByOwnerUserIdAndWorkspaceType(Long ownerUserId, WorkspaceType workspaceType);
}
