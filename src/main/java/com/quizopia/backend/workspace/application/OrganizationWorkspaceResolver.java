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
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    public OrganizationWorkspaceResolver(SchoolRepository schoolRepository,
                                         OrganizationRepository organizationRepository,
                                         WorkspaceRepository workspaceRepository,
                                         org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.schoolRepository = schoolRepository;
        this.organizationRepository = organizationRepository;
        this.workspaceRepository = workspaceRepository;
        this.jdbc = jdbc;
    }

    /**
     * Returns the organization workspace for the school, provisioning the
     * organization + workspace pair on first use and backfilling memberships
     * for the school's existing profile holders (mirroring the V10 backfill
     * rules: active ACADEMIC_ADMIN-with-profile → ADMIN, teacher → EDITOR,
     * student → MEMBER). Throws WORKSPACE_NOT_FOUND when the school itself
     * does not exist.
     */
    @Transactional
    public Long requireOrganizationWorkspaceId(Long schoolId) {
        Organization organization = organizationRepository.findByLegacySchoolId(schoolId)
                .orElseGet(() -> provisionOrganization(schoolId));
        Long workspaceId = workspaceRepository
                .findByOrganizationIdAndWorkspaceType(organization.getId(), WorkspaceType.ORGANIZATION)
                .map(Workspace::getId)
                .orElseGet(() -> provisionWorkspace(organization));
        backfillSchoolMemberships(schoolId, workspaceId);
        return workspaceId;
    }

    /**
     * Idempotently ensures every legacy profile holder of the school has an
     * ACTIVE membership in the organization workspace (V10 rules). Rows
     * created here are exactly what the V10 migration created for
     * pre-migration schools; post-migration schools get the same treatment
     * lazily. Existing memberships (any status, e.g. SUSPENDED/REMOVED) are
     * never overwritten.
     */
    private void backfillSchoolMemberships(Long schoolId, Long workspaceId) {
        jdbc.update("""
                INSERT INTO workspace_memberships (workspace_id, user_id, role, status)
                SELECT :workspaceId, p.user_id,
                       CASE
                           WHEN EXISTS (
                               SELECT 1 FROM user_roles ur
                               JOIN roles r ON r.id = ur.role_id
                               WHERE ur.user_id = p.user_id
                                 AND r.code = 'ACADEMIC_ADMIN'
                                 AND (ur.expires_at IS NULL OR ur.expires_at > now())
                           ) THEN 'ADMIN'
                           WHEN EXISTS (
                               SELECT 1 FROM teacher_profiles tp WHERE tp.user_id = p.user_id
                           ) THEN 'EDITOR'
                           ELSE 'MEMBER'
                       END,
                       'ACTIVE'
                FROM (
                    SELECT user_id, school_id FROM teacher_profiles
                    UNION
                    SELECT user_id, school_id FROM student_profiles
                ) p
                WHERE p.school_id = :schoolId
                ON CONFLICT DO NOTHING
                """,
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("workspaceId", workspaceId)
                        .addValue("schoolId", schoolId));
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
