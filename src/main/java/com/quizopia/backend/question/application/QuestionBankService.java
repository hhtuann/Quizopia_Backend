package com.quizopia.backend.question.application;

import com.quizopia.backend.academic.domain.model.AcademicStatus;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.domain.model.TeacherProfile;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.academic.repository.TeacherProfileRepository;
import com.quizopia.backend.common.BusinessCodes;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.question.domain.model.QuestionBank;
import com.quizopia.backend.question.dto.CreateQuestionBankRequest;
import com.quizopia.backend.question.dto.PageResponse;
import com.quizopia.backend.question.dto.QuestionBankListItem;
import com.quizopia.backend.question.dto.QuestionBankResponse;
import com.quizopia.backend.question.dto.QuestionSummary;
import com.quizopia.backend.question.dto.SubjectSummary;
import com.quizopia.backend.question.exception.QuestionErrorCode;
import com.quizopia.backend.question.exception.QuestionException;
import com.quizopia.backend.question.repository.QuestionBankRepository;
import com.quizopia.backend.workspace.application.WorkspaceAccessService;
import com.quizopia.backend.workspace.application.WorkspaceProvisioningService;
import com.quizopia.backend.workspace.domain.model.Organization;
import com.quizopia.backend.workspace.domain.model.Workspace;
import com.quizopia.backend.workspace.domain.model.WorkspaceType;
import com.quizopia.backend.workspace.exception.WorkspaceErrorCode;
import com.quizopia.backend.workspace.exception.WorkspaceException;
import com.quizopia.backend.workspace.repository.OrganizationRepository;
import com.quizopia.backend.workspace.repository.WorkspaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application service for the Question Bank API (Batch B1). Enforces
 * workspace ownership and state checks at the service layer (deny by
 * default). All list queries avoid N+1 via batch counts or native joins.
 *
 * <p>Public platform foundation (issue #1 A3): banks are owned by
 * (workspace, creating user). A newly registered user without a school or
 * teacher profile can create banks in their personal workspace. Legacy
 * school/subject/owner columns are retained as provenance for organization
 * workspaces.
 */
@Service
public class QuestionBankService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORT_ALLOWLIST = Set.of("createdAt", "code");

    private final TeacherProfileRepository teacherProfileRepository;
    private final SubjectRepository subjectRepository;
    private final QuestionBankRepository questionBankRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final com.quizopia.backend.workspace.application.OrganizationWorkspaceResolver organizationWorkspaceResolver;
    private final OrganizationRepository organizationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final WorkspaceProvisioningService workspaceProvisioningService;
    private final com.quizopia.backend.identity.repository.UserRepository userRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public QuestionBankService(TeacherProfileRepository teacherProfileRepository,
            SubjectRepository subjectRepository,
            QuestionBankRepository questionBankRepository,
            UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            com.quizopia.backend.workspace.application.OrganizationWorkspaceResolver organizationWorkspaceResolver,
            OrganizationRepository organizationRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceAccessService workspaceAccessService,
            WorkspaceProvisioningService workspaceProvisioningService,
            com.quizopia.backend.identity.repository.UserRepository userRepository,
            NamedParameterJdbcTemplate jdbc,
            Clock clock) {
        this.teacherProfileRepository = teacherProfileRepository;
        this.subjectRepository = subjectRepository;
        this.questionBankRepository = questionBankRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.organizationWorkspaceResolver = organizationWorkspaceResolver;
        this.organizationRepository = organizationRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.workspaceProvisioningService = workspaceProvisioningService;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ============================================================
    // POST /api/question-banks
    // ============================================================

    @Transactional
    public QuestionBankResponse createBank(Long userId, CreateQuestionBankRequest request) {
        requirePermission(userId, "QUESTION_BANK_CREATE");

        Workspace workspace = resolveAuthoringWorkspace(userId, request.workspaceId());
        TeacherProfile profile = teacherProfileRepository.findByUserId(userId).orElse(null);

        Subject subject = null;
        if (workspace.getWorkspaceType() == WorkspaceType.ORGANIZATION) {
            // Legacy academic rule: organization banks require an ACTIVE subject
            // of the organization's school.
            if (request.subjectId() == null) {
                throw new QuestionException(QuestionErrorCode.QUESTION_VALIDATION_ERROR,
                        "subjectId is required for organization workspace question banks");
            }
            Long schoolId = requireOrganizationSchoolId(workspace);
            subject = subjectRepository.findById(request.subjectId())
                    .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_SUBJECT_NOT_FOUND));
            if (!schoolId.equals(subject.getSchoolId())) {
                throw new QuestionException(QuestionErrorCode.QUESTION_SUBJECT_SCHOOL_MISMATCH);
            }
            if (subject.getStatus() != AcademicStatus.ACTIVE) {
                throw new QuestionException(QuestionErrorCode.QUESTION_SUBJECT_NOT_FOUND);
            }
        } else if (request.subjectId() != null) {
            // Personal workspace authoring is school-free in Phase 1 (ADR-001 §2.2).
            throw new QuestionException(QuestionErrorCode.QUESTION_VALIDATION_ERROR,
                    "subjectId is not supported for personal workspace question banks");
        }

        Long workspaceId = workspace.getId();
        String code = (request.code() == null || request.code().isBlank())
                ? BusinessCodes.readableCode("QB", 8,
                        c -> questionBankRepository
                                .existsByWorkspaceIdAndCreatedByUserUserIdAndCodeIgnoreCase(workspaceId, userId, c))
                : request.code().trim();
        QuestionBank bank = new QuestionBank(
                workspaceId,
                userId,
                subject != null ? subject.getSchoolId() : null,
                subject != null ? subject.getId() : null,
                profile != null ? profile.getId() : null,
                code,
                request.name());
        bank.setDescription(request.description());

        try {
            questionBankRepository.saveAndFlush(bank);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            if (isOwnerCodeUniqueViolation(ex)) {
                throw new QuestionException(QuestionErrorCode.QUESTION_BANK_CODE_CONFLICT);
            }
            throw ex;
        }

        return new QuestionBankResponse(
                bank.getId(),
                bank.getCode(),
                bank.getName(),
                bank.getDescription(),
                subject != null
                        ? new SubjectSummary(subject.getId(), subject.getCode(), subject.getName())
                        : null,
                0,
                bank.getCreatedAt());
    }

    // ============================================================
    // GET /api/question-banks/my
    // ============================================================

    @Transactional(readOnly = true)
    public PageResponse<QuestionBankListItem> listMyBanks(
            Long userId, String search, Long subjectId, int page, int size, String sort) {

        requirePermission(userId, "QUESTION_BANK_READ");
        Pageable pageable = safePageable(page, size, sort);

        Page<QuestionBank> banks = questionBankRepository.findCreatedByUser(
                userId,
                (search == null || search.isBlank()) ? null : search,
                subjectId,
                pageable);

        // Batch question counts + subject summaries (no N+1)
        Map<Long, Long> counts = countQuestionsForBanks(banks.getContent());
        Map<Long, SubjectSummary> subjects = batchSubjectSummaries(banks.getContent());

        List<QuestionBankListItem> items = banks.getContent().stream()
                .map(b -> new QuestionBankListItem(
                        b.getId(),
                        b.getCode(),
                        b.getName(),
                        b.getDescription(),
                        subjects.get(b.getSubjectId()),
                        counts.getOrDefault(b.getId(), 0L),
                        b.getStatus().name(),
                        b.getCreatedAt()))
                .toList();

        return new PageResponse<>(
                items, banks.getNumber(), banks.getSize(),
                banks.getTotalElements(), banks.getTotalPages(),
                pageable.getSort().toString());
    }

    // ============================================================
    // GET /api/question-banks/{bankId}/questions
    // ============================================================

    @Transactional(readOnly = true)
    public PageResponse<QuestionSummary> listQuestions(
            Long userId, Long bankId, String type, String search, String status,
            int page, int size) {

        requirePermission(userId, "QUESTION_READ");

        QuestionBank bank = questionBankRepository.findById(bankId)
                .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_BANK_NOT_FOUND));

        if (!bank.getCreatedByUserUserId().equals(userId)) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
        if (bank.getStatus() != com.quizopia.backend.question.domain.model.QuestionBankStatus.ACTIVE) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        int offset = safePage * safeSize;

        // Build dynamic WHERE (safe: only fixed column names, parameters bound)
        StringBuilder where = new StringBuilder("WHERE q.question_bank_id = :bankId ");
        MapSqlParameterSource params = new MapSqlParameterSource("bankId", bankId);

        if (type != null && !type.isBlank()) {
            where.append("AND qv.question_type = :type ");
            params.addValue("type", type);
        }
        if (search != null && !search.isBlank()) {
            where.append("AND (LOWER(q.code) LIKE LOWER(:search) OR LOWER(qv.content) LIKE LOWER(:search)) ");
            params.addValue("search", "%" + search + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append("AND q.status = :status ");
            params.addValue("status", status);
        }

        // Count
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions q "
                        + "JOIN question_versions qv ON qv.question_id = q.id "
                        + "AND qv.version_number = q.current_version_number "
                        + where,
                params, Long.class);
        if (total == null)
            total = 0L;

        // Data (join current version — no N+1)
        MapSqlParameterSource dataParams = params.addValue("limit", safeSize).addValue("offset", offset);
        List<QuestionSummary> items = jdbc.query(
                "SELECT q.id, q.code, q.current_version_number, qv.question_type, qv.content, "
                        + "qv.difficulty, qv.default_points, q.status, q.created_at "
                        + "FROM questions q "
                        + "JOIN question_versions qv ON qv.question_id = q.id "
                        + "AND qv.version_number = q.current_version_number "
                        + where
                        + "ORDER BY q.created_at DESC, q.id DESC "
                        + "LIMIT :limit OFFSET :offset",
                dataParams,
                (rs, rowNum) -> new QuestionSummary(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getInt("current_version_number"),
                        rs.getString("question_type"),
                        rs.getString("content"),
                        rs.getString("difficulty"),
                        rs.getBigDecimal("default_points"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()));

        int totalPages = safeSize > 0 ? (int) Math.ceil((double) total / safeSize) : 0;
        return new PageResponse<>(items, safePage, safeSize, total, totalPages, "createdAt: DESC");
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Resolves the authoring workspace for a create request (issue #1 A3):
     * explicit {@code workspaceId} (validated for content-editor rights) or,
     * by default, the caller's organization workspace when they hold a teacher
     * profile, else their personal workspace (provisioned on demand).
     */
    private Workspace resolveAuthoringWorkspace(Long userId, Long requestedWorkspaceId) {
        if (requestedWorkspaceId != null) {
            workspaceAccessService.requireContentEditor(userId, requestedWorkspaceId);
            return workspaceRepository.findById(requestedWorkspaceId)
                    .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
        }
        TeacherProfile profile = teacherProfileRepository.findByUserId(userId).orElse(null);
        if (profile != null) {
            Long workspaceId = organizationWorkspaceResolver
                    .requireOrganizationWorkspaceId(profile.getSchoolId());
            return workspaceRepository.findById(workspaceId)
                    .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
        }
        return workspaceProvisioningService
                .ensurePersonalWorkspace(requireUser(userId));
    }

    private Long requireOrganizationSchoolId(Workspace workspace) {
        return organizationRepository.findById(workspace.getOrganizationId())
                .map(Organization::getLegacySchoolId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "Organization workspace has no organization"));
    }

    private com.quizopia.backend.identity.domain.model.User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED));
    }

    private Pageable safePageable(int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Sort safeSort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String prop = parts[0].trim();
            Sort.Direction dir = (parts.length > 1
                    && parts[1].trim().equalsIgnoreCase("asc"))
                            ? Sort.Direction.ASC
                            : Sort.Direction.DESC;
            if (SORT_ALLOWLIST.contains(prop)) {
                safeSort = Sort.by(dir, prop).and(Sort.by(Sort.Direction.DESC, "id"));
            }
        } else {
            safeSort = safeSort.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        return PageRequest.of(safePage, safeSize, safeSort);
    }

    @SuppressWarnings("null")
    private Map<Long, Long> countQuestionsForBanks(List<QuestionBank> banks) {
        if (banks.isEmpty())
            return Map.of();
        List<Long> bankIds = banks.stream().map(QuestionBank::getId).toList();
        List<Object[]> rows = questionBankRepository.countQuestionsByBankIds(bankIds);
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    @SuppressWarnings("null")
    private Map<Long, SubjectSummary> batchSubjectSummaries(List<QuestionBank> banks) {
        Set<Long> subjectIds = banks.stream().map(QuestionBank::getSubjectId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (subjectIds.isEmpty())
            return Map.of();
        return subjectRepository.findAllById(subjectIds).stream()
                .collect(Collectors.toMap(Subject::getId,
                        s -> new SubjectSummary(s.getId(), s.getCode(), s.getName())));
    }

    private void requirePermission(Long userId, String permissionCode) {
        List<String> permissions = rolePermissionRepository
                .findEffectivePermissionCodesByUserId(userId, Instant.now(clock));
        if (!permissions.contains(permissionCode)) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
    }

    private static boolean isOwnerCodeUniqueViolation(
            org.springframework.dao.DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && (name.contains("uk_question_banks_owner_code_ci")
                        || name.contains("uk_question_banks_workspace_creator_code_ci"))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}
