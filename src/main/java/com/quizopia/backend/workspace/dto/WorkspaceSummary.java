package com.quizopia.backend.workspace.dto;

import java.time.Instant;

/**
 * A workspace the caller may activate: only id, type, name and the caller's
 * own membership state. Never exposes other users' membership information.
 */
public record WorkspaceSummary(
        Long workspaceId,
        String workspaceType,
        String name,
        Long organizationId,
        String role,
        String status,
        Instant memberSince) {
}
