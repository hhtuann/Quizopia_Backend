package com.quizopia.backend.workspace.application;

import com.quizopia.backend.academic.domain.model.School;
import com.quizopia.backend.academic.repository.SchoolRepository;
import com.quizopia.backend.workspace.domain.model.Organization;
import com.quizopia.backend.workspace.domain.model.Workspace;
import com.quizopia.backend.workspace.domain.model.WorkspaceType;
import com.quizopia.backend.workspace.exception.WorkspaceErrorCode;
import com.quizopia.backend.workspace.exception.WorkspaceException;
import com.quizopia.backend.workspace.repository.OrganizationRepository;
import com.quizopia.backend.workspace.repository.WorkspaceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves (and lazily provisions) the organization workspace for a legacy
 * school (ADR-001 §2.1). Schools created after the V10 migration have no
 * organization until first referenced; this resolver mirrors the V10 backfill
 * deterministically: one organization ({@code legacy_school_id} provenance)
 * plus one ORGANIZATION workspace, both idempotent under concurrency via the
 * DB unique constraints.
 */
@Service
public class OrganizationWorkspaceResolver {

    private final SchoolRepository schoolRepository;
    private final OrganizationRepository organizationRepository;
    private final WorkspaceRepository workspaceRepository;

    public OrganizationWorkspaceResolver(SchoolRepository schoolRepository,
                                         OrganizationRepository organizationRepository,
                                         WorkspaceRepository workspaceRepository) {
        this.schoolRepository = schoolRepository;
        this.organizationRepository = organizationRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Returns the organization workspace for the school, provisioning the
     * organization + workspace pair on first use. Throws WORKSPACE_NOT_FOUND
     * when the school itself does not exist.
     */
    @Transactional
    public Long requireOrganizationWorkspaceId(Long schoolId) {
        Organization organization = organizationRepository.findByLegacySchoolId(schoolId)
                .orElseGet(() -> provisionOrganization(schoolId));
        return workspaceRepository
                .findByOrganizationIdAndWorkspaceType(organization.getId(), WorkspaceType.ORGANIZATION)
                .map(Workspace::getId)
                .orElseGet(() -> provisionWorkspace(organization));
    }

    /**
     * Returns the organization for the school, provisioning it on first use
     * (for callers that also need membership provisioning).
     */
    @Transactional
    public Organization requireOrganization(Long schoolId) {
        return organizationRepository.findByLegacySchoolId(schoolId)
                .orElseGet(() -> provisionOrganization(schoolId));
    }

    private Organization provisionOrganization(Long schoolId) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "School " + schoolId + " does not exist"));
        try {
            return organizationRepository.saveAndFlush(
                    new Organization(school.getCode(), school.getName(), schoolId));
        } catch (DataIntegrityViolationException race) {
            // Concurrent provisioning won the unique race; adopt its row.
            return organizationRepository.findByLegacySchoolId(schoolId).orElseThrow(() -> race);
        }
    }

    private Long provisionWorkspace(Organization organization) {
        try {
            return workspaceRepository.saveAndFlush(
                    Workspace.organizational(organization.getId(), organization.getName())).getId();
        } catch (DataIntegrityViolationException race) {
            return workspaceRepository
                    .findByOrganizationIdAndWorkspaceType(organization.getId(), WorkspaceType.ORGANIZATION)
                    .map(Workspace::getId)
                    .orElseThrow(() -> race);
        }
    }
}
