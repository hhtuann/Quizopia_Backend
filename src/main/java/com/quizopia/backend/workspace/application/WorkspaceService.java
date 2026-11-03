package com.quizopia.backend.workspace.application;

import com.quizopia.backend.workspace.domain.model.Workspace;
import com.quizopia.backend.workspace.domain.model.WorkspaceMembership;
import com.quizopia.backend.workspace.domain.model.WorkspaceMembershipStatus;
import com.quizopia.backend.workspace.domain.model.WorkspaceRole;
import com.quizopia.backend.workspace.dto.MyWorkspacesResponse;
import com.quizopia.backend.workspace.dto.WorkspaceSummary;
import com.quizopia.backend.workspace.exception.WorkspaceErrorCode;
import com.quizopia.backend.workspace.exception.WorkspaceException;
import com.quizopia.backend.workspace.repository.WorkspaceMembershipRepository;
import com.quizopia.backend.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side workspace queries (ADR-001 §2.3). {@code GET /api/workspaces/my}
 * returns only the workspaces the caller can activate — never other users'
 * personal workspace information.
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository membershipRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            WorkspaceMembershipRepository membershipRepository) {
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
    }

    @Transactional(readOnly = true)
    public MyWorkspacesResponse myWorkspaces(Long userId) {
        List<WorkspaceMembership> memberships =
                membershipRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, WorkspaceMembershipStatus.ACTIVE);

        Map<Long, WorkspaceMembership> byWorkspace = new LinkedHashMap<>();
        for (WorkspaceMembership membership : memberships) {
            byWorkspace.put(membership.getWorkspaceId(), membership);
        }
        if (byWorkspace.isEmpty()) {
            return MyWorkspacesResponse.of(List.of());
        }

        List<WorkspaceSummary> summaries = workspaceRepository.findAllById(byWorkspace.keySet()).stream()
                .map(workspace -> toSummary(workspace, byWorkspace.get(workspace.getId())))
                .toList();

        return MyWorkspacesResponse.of(summaries);
    }

    private static WorkspaceSummary toSummary(Workspace workspace, WorkspaceMembership membership) {
        return new WorkspaceSummary(
                workspace.getId(),
                workspace.getWorkspaceType().name(),
                workspace.getName(),
                workspace.getOrganizationId(),
                membership.getRole().name(),
                membership.getStatus().name(),
                membership.getCreatedAt());
    }

    /**
     * Loads a workspace or throws WORKSPACE_NOT_FOUND (anti-enumeration).
     */
    @Transactional(readOnly = true)
    public Workspace requireWorkspace(Long workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
    }

    /**
     * Loads the caller's ACTIVE membership or throws WORKSPACE_ACCESS_DENIED.
     * Inactive (INVITED/SUSPENDED/REMOVED) memberships are denied.
     */
    @Transactional(readOnly = true)
    public WorkspaceMembership requireActiveMembership(Long userId, Long workspaceId) {
        return membershipRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .filter(WorkspaceMembership::isActive)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED));
    }

    /**
     * Requires an ACTIVE membership whose role is one of {@code allowed}.
     * Rejects workspace privilege escalation even when IDs are manipulated.
     */
    @Transactional(readOnly = true)
    public WorkspaceMembership requireAnyWorkspaceRole(Long userId, Long workspaceId, WorkspaceRole... allowed) {
        WorkspaceMembership membership = requireActiveMembership(userId, workspaceId);
        for (WorkspaceRole role : allowed) {
            if (membership.getRole() == role) {
                return membership;
            }
        }
        throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ROLE_DENIED);
    }
}
