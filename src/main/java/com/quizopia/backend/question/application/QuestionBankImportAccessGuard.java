package com.quizopia.backend.question.application;

import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.question.domain.model.QuestionBank;
import com.quizopia.backend.question.domain.model.QuestionBankStatus;
import com.quizopia.backend.question.exception.QuestionErrorCode;
import com.quizopia.backend.question.exception.QuestionException;
import com.quizopia.backend.question.repository.QuestionBankRepository;
import com.quizopia.backend.workspace.application.WorkspaceAccessService;
import com.quizopia.backend.workspace.exception.WorkspaceException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Shared authorization boundary for writing questions into a Question Bank
 * (Excel import, Markdown import and single-question editing).
 *
 * <p>Policy (P2-C, issue #18 authorization audit — supersedes the legacy
 * {@code TeacherProfile}/school-same checks that predate the Phase 1
 * workspace migration and could not express personal-workspace banks):
 *
 * <ol>
 *   <li>active {@code TEACHER} role (deny by default);</li>
 *   <li>{@code QUESTION_CREATE} effective permission;</li>
 *   <li>bank exists (404) and is {@code ACTIVE};</li>
 *   <li>caller is the bank creator ({@code created_by_user_id});</li>
 *   <li>Phase 1 resource boundary (issue #4 P0-2): personal workspace →
 *       owner only; organization workspace → ACTIVE membership required —
 *       creation-time ownership never overrides a revoked membership.</li>
 * </ol>
 *
 * <p>This mirrors {@code QuestionBankService.listQuestions} (creator equality
 * on top of the workspace boundary) at manage level, so a self-upgraded
 * teacher without a {@code TeacherProfile} can import into their personal
 * bank while unrelated teachers, revoked members and non-teachers are denied.
 * UI gating is never authorization; every import/edit path re-runs this
 * check server-side.
 */
@Service
public class QuestionBankImportAccessGuard {

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final QuestionBankRepository questionBankRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final Clock clock;

    public QuestionBankImportAccessGuard(UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            QuestionBankRepository questionBankRepository,
            WorkspaceAccessService workspaceAccessService,
            Clock clock) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.questionBankRepository = questionBankRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.clock = clock;
    }

    /**
     * Runs the full deny-by-default import authorization and returns the
     * authorized bank.
     *
     * @throws QuestionException QUESTION_BANK_ACCESS_DENIED (role / permission /
     *                           creator / workspace / non-ACTIVE) or
     *                           QUESTION_BANK_NOT_FOUND
     */
    public QuestionBank requireImportAccess(Long userId, Long bankId) {
        Instant now = Instant.now(clock);
        requireActiveTeacherRole(userId, now);
        requirePermission(userId, "QUESTION_CREATE", now);

        QuestionBank bank = questionBankRepository.findById(bankId)
                .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_BANK_NOT_FOUND));

        // Null-safe: deny when creator provenance is absent (e.g. a
        // legacy-ctor entity cached in-session before the ownership trigger
        // is visible) rather than NPE.
        if (bank.getCreatedByUserUserId() == null
                || !bank.getCreatedByUserUserId().equals(userId)) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
        try {
            workspaceAccessService.requireResourceManage(
                    userId, bank.getWorkspaceId(), bank.getCreatedByUserUserId());
        } catch (WorkspaceException ex) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
        if (bank.getStatus() != QuestionBankStatus.ACTIVE) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
        return bank;
    }

    private void requireActiveTeacherRole(Long userId, Instant now) {
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
        if (!roles.contains("TEACHER")) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
    }

    private void requirePermission(Long userId, String permission, Instant now) {
        List<String> perms = rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now);
        if (!perms.contains(permission)) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
    }
}
