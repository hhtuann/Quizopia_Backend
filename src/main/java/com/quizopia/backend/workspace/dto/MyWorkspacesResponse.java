package com.quizopia.backend.workspace.dto;

import java.util.List;

public record MyWorkspacesResponse(
        List<WorkspaceSummary> workspaces,
        Long personalWorkspaceId) {

    public static MyWorkspacesResponse of(List<WorkspaceSummary> workspaces) {
        Long personal = workspaces.stream()
                .filter(w -> "PERSONAL".equals(w.workspaceType()))
                .map(WorkspaceSummary::workspaceId)
                .findFirst()
                .orElse(null);
        return new MyWorkspacesResponse(workspaces, personal);
    }
}
