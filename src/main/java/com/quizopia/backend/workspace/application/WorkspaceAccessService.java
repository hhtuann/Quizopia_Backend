package com.quizopia.backend.workspace.application;

import com.quizopia.backend.workspace.domain.model.Workspace;
import com.quizopia.backend.workspace.domain.model.WorkspaceMembership;
import com.quizopia.backend.workspace.domain.model.WorkspaceMembershipStatus;
import com.quizopia.backend.workspace.domain.model.WorkspaceRole;
import com.quizopia.backend.workspace.domain.model.WorkspaceType;
import com.quizopia.backend.workspace.exception.WorkspaceErrorCode;
import com.quizopia.backend.workspace.exception.WorkspaceException;
import com.quizopia.backend.workspace.repository.WorkspaceMembershipRepository;
import com.quizopia.backend.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable workspace policy checks for backend services/controllers
 * (issue #1 A2). A single implementation shared by all modules — layered on
 * top of the existing DB-backed permission checks, replacing school-same
 * checks as the resource-boundary guard.
 *
 * <p>Rule: ownership + active membership + object relation are always
 * validated server-side; inactive memberships and revoked roles are denied.
 *
 * <p>Resource-boundary policy (issue #4 P0-2): in ORGANIZATION workspaces,
 * creation-time ownership never overrides a revoked membership — see
 * {@link #requireResourceRead} / {@link #requireResourceManage}.
 */
@Service
public class WorkspaceAccessService {

    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository membershipRepository;

    public WorkspaceAccessService(WorkspaceService workspaceService,
                                  WorkspaceRepository workspaceRepository,
                                  WorkspaceMembershipRepository membershipRepository) {
        this.workspaceService = workspaceService;
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * Ensures the workspace exists (404) and the user holds an ACTIVE
     * membership (403). Returns the workspace id for chaining.
     */
    @Transactional(readOnly = true)
    public Long requireActiveMember(Long userId, Long workspaceId) {
        workspaceService.requireWorkspace(workspaceId);
        workspaceService.requireActiveMembership(userId, workspaceId);
        return workspaceId;
    }

    /**
     * Ensures the workspace exists and the user's ACTIVE membership role is
     * one of {@code allowed} (403 otherwise).
     */
    @Transactional(readOnly = true)
    public void requireAnyWorkspaceRole(Long userId, Long workspaceId, WorkspaceRole... allowed) {
        workspaceService.requireWorkspace(workspaceId);
        workspaceService.requireAnyWorkspaceRole(userId, workspaceId, allowed);
    }

    /**
     * Requires the user to be able to manage content in the workspace:
     * OWNER, ADMIN or EDITOR.
     */
    @Transactional(readOnly = true)
    public void requireContentEditor(Long userId, Long workspaceId) {
        requireAnyWorkspaceRole(userId, workspaceId,
                WorkspaceRole.OWNER, WorkspaceRole.ADMIN, WorkspaceRole.EDITOR);
    }

    /**
     * Requires the user to administer the workspace: OWNER or ADMIN.
     */
    @Transactional(readOnly = true)
    public void requireWorkspaceAdmin(Long userId, Long workspaceId) {
        requireAnyWorkspaceRole(userId, workspaceId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
    }

    /**
     * Returns true when the user holds an ACTIVE membership in the workspace
     * (no exception) — for optional visibility filters.
     */
    @Transactional(readOnly = true)
    public boolean isActiveMember(Long userId, Long workspaceId) {
        try {
            workspaceService.requireActiveMembership(userId, workspaceId);
            return true;
        } catch (WorkspaceException ex) {
            return false;
        }
    }

    /**
     * Returns the ACTIVE membership when present, null otherwise.
     */
    @Transactional(readOnly = true)
    public WorkspaceMembership activeMembership(Long userId, Long workspaceId) {
        try {
            return workspaceService.requireActiveMembership(userId, workspaceId);
        } catch (WorkspaceException ex) {
            return null;
        }
    }

    /**
     * Ensures a workspace id is supplied (bad request when absent).
     */
    public Long requireWorkspaceId(Long workspaceId) {
        if (workspaceId == null) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_VALIDATION_ERROR,
                    "workspaceId must not be null");
        }
        return workspaceId;
    }

    // ============================================================
    // Resource-boundary policy (issue #4 P0-2)
    //
    // A resource (question bank, exam, session, classroom) lives in a
    // workspace. Creation-time ownership NEVER overrides a revoked
    // organization membership: in ORGANIZATION workspaces every access —
    // read included — requires an ACTIVE membership, and management
    // additionally requires to be the creator or a workspace OWNER/ADMIN.
    // PERSONAL workspaces keep the authenticated true owner.
    // ============================================================

    /**
     * Read access: PERSONAL → the owner only; ORGANIZATION → any ACTIVE
     * member (creator included; revoked creators are denied).
     */
    @Transactional(readOnly = true)
    public void requireResourceRead(Long userId, Long workspaceId) {
        Workspace workspace = workspaceService.requireWorkspace(workspaceId);
        if (workspace.isPersonal()) {
            if (!userId.equals(workspace.getOwnerUserId())) {
                throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
            }
            return;
        }
        requireActiveMembershipInternal(userId, workspaceId);
    }

    /**
     * Management access: PERSONAL → the owner; ORGANIZATION → ACTIVE
     * membership AND (creator OR workspace OWNER/ADMIN).
     */
    @Transactional(readOnly = true)
    public void requireResourceManage(Long userId, Long workspaceId, Long creatorUserId) {
        Workspace workspace = workspaceService.requireWorkspace(workspaceId);
        if (workspace.isPersonal()) {
            if (!userId.equals(workspace.getOwnerUserId())) {
                throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
            }
            return;
        }
        WorkspaceMembership membership = requireActiveMembershipInternal(userId, workspaceId);
        if (creatorUserId != null && creatorUserId.equals(userId)) {
            return;
        }
        if (membership.getRole() == WorkspaceRole.OWNER || membership.getRole() == WorkspaceRole.ADMIN) {
            return;
        }
        throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ROLE_DENIED);
    }

    /**
     * Workspace ids whose resources the user may still see: the personal
     * workspace (if provisioned) plus every ORGANIZATION workspace with an
     * ACTIVE membership. Used to scope list queries so revoked members'
     * creations disappear from their lists.
     */
    @Transactional(readOnly = true)
    public List<Long> accessibleWorkspaceIds(Long userId) {
        List<Long> ids = new ArrayList<>();
        workspaceRepository
                .findByOwnerUserIdAndWorkspaceType(userId, WorkspaceType.PERSONAL)
                .ifPresent(ws -> ids.add(ws.getId()));
        for (WorkspaceMembership membership : membershipRepository
                .findByUserIdAndStatusOrderByCreatedAtAsc(userId, WorkspaceMembershipStatus.ACTIVE)) {
            ids.add(membership.getWorkspaceId());
        }
        return ids;
    }

    private WorkspaceMembership requireActiveMembershipInternal(Long userId, Long workspaceId) {
        return membershipRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .filter(WorkspaceMembership::isActive)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED));
    }
}
