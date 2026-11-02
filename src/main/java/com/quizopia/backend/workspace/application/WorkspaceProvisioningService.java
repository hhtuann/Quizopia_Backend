package com.quizopia.backend.workspace.application;

import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.workspace.domain.model.Workspace;
import com.quizopia.backend.workspace.domain.model.WorkspaceMembership;
import com.quizopia.backend.workspace.domain.model.WorkspaceRole;
import com.quizopia.backend.workspace.domain.model.WorkspaceType;
import com.quizopia.backend.workspace.repository.WorkspaceMembershipRepository;
import com.quizopia.backend.workspace.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional, idempotent provisioning of personal workspaces (ADR-001 §2.1).
 *
 * <p>Every user — self-registered or administrator-created — gets exactly one
 * PERSONAL workspace with an OWNER membership. The DB partial unique index
 * {@code uk_workspaces_personal_owner} is the concurrency backstop: when a
 * concurrent transaction wins the race, the insert violates the index and the
 * loser re-fetches the winning row instead of failing user creation.
 */
@Service
public class WorkspaceProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProvisioningService.class);

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository membershipRepository;

    public WorkspaceProvisioningService(WorkspaceRepository workspaceRepository,
                                        WorkspaceMembershipRepository membershipRepository) {
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * Ensures the given user owns exactly one ACTIVE personal workspace.
     * Safe to call repeatedly and under concurrency.
     *
     * @return the personal workspace (never null)
     */
    @Transactional
    public Workspace ensurePersonalWorkspace(User user) {
        return workspaceRepository
                .findByOwnerUserIdAndWorkspaceType(user.getId(), WorkspaceType.PERSONAL)
                .map(this::ensureOwnerMembershipActive)
                .orElseGet(() -> createPersonalWorkspace(user));
    }

    private Workspace ensureOwnerMembershipActive(Workspace workspace) {
        membershipRepository.findByWorkspaceIdAndUserId(workspace.getId(), workspace.getOwnerUserId())
                .ifPresentOrElse(membership -> {
                    if (!membership.isActive()) {
                        throw new IllegalStateException(
                                "Personal workspace " + workspace.getId() + " membership is not ACTIVE");
                    }
                }, () -> createOwnerMembership(workspace));
        return workspace;
    }

    private Workspace createPersonalWorkspace(User user) {
        Workspace workspace = Workspace.personal(user.getId(), workspaceLabel(user));
        try {
            workspace = workspaceRepository.saveAndFlush(workspace);
        } catch (DataIntegrityViolationException race) {
            // Concurrent provisioning won the unique-index race; adopt its row.
            Workspace winner = workspaceRepository
                    .findByOwnerUserIdAndWorkspaceType(user.getId(), WorkspaceType.PERSONAL)
                    .orElseThrow(() -> race);
            log.debug("Personal workspace for user {} provisioned concurrently: workspace {}",
                    user.getId(), winner.getId());
            return ensureOwnerMembershipActive(winner);
        }
        createOwnerMembership(workspace);
        log.info("Provisioned personal workspace {} for user {}", workspace.getId(), user.getId());
        return workspace;
    }

    private void createOwnerMembership(Workspace workspace) {
        try {
            membershipRepository.saveAndFlush(
                    new WorkspaceMembership(workspace.getId(), workspace.getOwnerUserId(), WorkspaceRole.OWNER));
        } catch (DataIntegrityViolationException race) {
            log.debug("OWNER membership for workspace {} provisioned concurrently", workspace.getId());
        }
    }

    private static String workspaceLabel(User user) {
        String displayName = user.getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        return user.getUsername();
    }
}
