package com.quizopia.backend.workspace.application;

import com.quizopia.backend.workspace.domain.model.WorkspaceMembership;
import com.quizopia.backend.workspace.domain.model.WorkspaceRole;
import com.quizopia.backend.workspace.exception.WorkspaceErrorCode;
import com.quizopia.backend.workspace.exception.WorkspaceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reusable workspace policy checks for backend services/controllers
 * (issue #1 A2). A single implementation shared by all modules — layered on
 * top of the existing DB-backed permission checks, replacing school-same
 * checks as the resource-boundary guard.
 *
 * <p>Rule: ownership + active membership + object relation are always
 * validated server-side; inactive memberships and revoked roles are denied.
 */
@Service
public class WorkspaceAccessService {

    private final WorkspaceService workspaceService;

    public WorkspaceAccessService(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
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
}
