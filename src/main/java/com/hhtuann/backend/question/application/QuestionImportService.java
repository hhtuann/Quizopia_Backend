package com.hhtuann.backend.question.application;

import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.common.BusinessCodes;
import com.hhtuann.backend.identity.repository.RolePermissionRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import com.hhtuann.backend.question.domain.model.*;
import com.hhtuann.backend.question.dto.*;
import com.hhtuann.backend.question.exception.QuestionErrorCode;
import com.hhtuann.backend.question.exception.QuestionException;
import com.hhtuann.backend.question.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Application service that persists parser-validated question rows into a bank.
 *
 * <p>
 * Authorization (deny by default): active TEACHER role → QUESTION_CREATE
 * permission → TeacherProfile → bank ownership + school scope + ACTIVE state.
 *
 * <p>
 * Duplicate detection: existing bank codes are loaded in a single batch
 * query; parser-valid rows matching an existing code are reported as row errors
 * (not persisted). Concurrent duplicate race is caught at flush time via the
 * database unique constraint and rolls back the entire valid batch.
 *
 * <p>
 * Persistence is all-or-nothing: every row that survives both parser and
 * DB-duplicate checks is persisted in a single transaction.
 */
@Service
public class QuestionImportService {

    private static final String DUPLICATE_CONSTRAINT = "uk_questions_bank_code_ci";

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final QuestionBankRepository questionBankRepository;
    private final QuestionRepository questionRepository;
    private final QuestionVersionRepository versionRepository;
    private final QuestionOptionRepository optionRepository;
    private final Clock clock;
    private final com.hhtuann.backend.notification.application.NotificationService notificationService;

    public QuestionImportService(UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            TeacherProfileRepository teacherProfileRepository,
            QuestionBankRepository questionBankRepository,
            QuestionRepository questionRepository,
            QuestionVersionRepository versionRepository,
            QuestionOptionRepository optionRepository,
            Clock clock,
            com.hhtuann.backend.notification.application.NotificationService notificationService) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.teacherProfileRepository = teacherProfileRepository;
        this.questionBankRepository = questionBankRepository;
        this.questionRepository = questionRepository;
        this.versionRepository = versionRepository;
        this.optionRepository = optionRepository;
        this.clock = clock;
        this.notificationService = notificationService;
    }

    @SuppressWarnings("null")
    @Transactional
    public ImportResponse importParsedQuestions(Long userId, Long bankId, ImportResult parseResult) {
        // --- Authorization (deny by default) ---
        Instant now = Instant.now(clock);
        requireActiveTeacherRole(userId, now);
        requirePermission(userId, "QUESTION_CREATE", now);
        TeacherProfile profile = resolveTeacherProfile(userId, now);

        QuestionBank bank = questionBankRepository.findById(bankId)
                .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_BANK_NOT_FOUND));
        requireOwnershipAndSchool(bank, profile);

        // --- Phase 2: persist (question codes are auto-generated; no pre-flight dup check) ---
        List<RowError> allErrors = new ArrayList<>(parseResult.errors());
        List<ValidQuestionRow> toPersist = parseResult.validRows();

        int importedRows = 0;
        if (!toPersist.isEmpty()) {
            try {
                for (ValidQuestionRow row : toPersist) {
                    persistRow(row, bank.getId(), userId);
                }
                questionRepository.flush();
                importedRows = toPersist.size();
            } catch (DataIntegrityViolationException ex) {
                if (isBankCodeUniqueViolation(ex)) {
                    throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_DUPLICATE_CODE);
                }
                throw ex;
            }
        }

        // Sort errors by rowNumber ascending, stable for same row.
        allErrors.sort(Comparator.comparingInt(RowError::rowNumber));

        long invalidRows = allErrors.stream().map(RowError::rowNumber).distinct().count();

        // Notify the teacher that the import is done.
        notificationService.create(userId,
                com.hhtuann.backend.notification.domain.model.NotificationType.IMPORT_COMPLETED,
                "Import completed",
                importedRows + "/" + parseResult.totalRows() + " questions imported" + (invalidRows > 0 ? " (" + invalidRows + " errors)" : ""),
                "/question-banks/" + bankId);

        return new ImportResponse(
                parseResult.totalRows(),
                importedRows,
                (int) invalidRows,
                allErrors);
    }

    /**
     * Preflight authorization (read-only, NOT {@code @Transactional}). Runs the
     * exact same deny-by-default checks as the top of
     * {@link #importParsedQuestions(Long, Long, ImportResult)} but without
     * opening a persistence transaction, so the controller can reject an
     * unauthorized caller <em>before</em> spending cycles parsing the workbook.
     *
     * <p>
     * The {@code @Transactional} {@code importParsedQuestions} re-runs these
     * same checks inside its transaction; this method is purely an early-out
     * optimisation and security gate at the API layer.
     *
     * @throws QuestionException QUESTION_BANK_ACCESS_DENIED (role / permission /
     *                           ownership / school / non-ACTIVE),
     *                           QUESTION_TEACHER_PROFILE_NOT_FOUND,
     *                           or QUESTION_BANK_NOT_FOUND
     */
    public void authorizeImportAccess(Long userId, Long bankId) {
        Instant now = Instant.now(clock);
        requireActiveTeacherRole(userId, now);
        requirePermission(userId, "QUESTION_CREATE", now);
        TeacherProfile profile = resolveTeacherProfile(userId, now);
        QuestionBank bank = questionBankRepository.findById(bankId)
                .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_BANK_NOT_FOUND));
        requireOwnershipAndSchool(bank, profile);
    }

    // ============================================================
    // Authorization helpers
    // ============================================================

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

    private TeacherProfile resolveTeacherProfile(Long userId, Instant now) {
        return teacherProfileRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
                    if (roles.contains("TEACHER")) {
                        return new QuestionException(QuestionErrorCode.QUESTION_TEACHER_PROFILE_NOT_FOUND);
                    }
                    return new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
                });
    }

    private void requireOwnershipAndSchool(QuestionBank bank, TeacherProfile profile) {
        if (!bank.getOwnerTeacherId().equals(profile.getId())) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
        if (!bank.getSchoolId().equals(profile.getSchoolId())) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
        if (bank.getStatus() != QuestionBankStatus.ACTIVE) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
    }

    // ============================================================
    // Entity mapping + persistence (one row at a time, same tx)
    // ============================================================

    private void persistRow(ValidQuestionRow row, Long bankId, Long userId) {
        String code = BusinessCodes.readableCode("QU", 8,
                c -> questionRepository.existsByQuestionBankIdAndCodeIgnoreCase(bankId, c));
        Question question = questionRepository.saveAndFlush(
                new Question(bankId, code, userId));

        QuestionVersion version = buildVersion(question.getId(), row, userId);
        versionRepository.saveAndFlush(version);

        List<QuestionOption> options = buildOptions(row, version.getId());
        if (!options.isEmpty()) {
            optionRepository.saveAll(options);
        }
    }

    private QuestionVersion buildVersion(Long questionId, ValidQuestionRow row, Long userId) {
        return new QuestionVersion(
                questionId,
                1,
                row.questionType(),
                row.content(),
                row.explanation(),
                row.difficulty(),
                java.math.BigDecimal.ONE,
                buildAnswerKey(row),
                JsonNodeFactory.instance.objectNode(), // metadata = empty {}
                userId);
    }

    /**
     * Builds the answer_key JSONB for NUMERIC_FILL only. Choice and
     * TRUE_FALSE_MATRIX types return {@code null} (the answer lives in
     * {@code question_options.is_correct}).
     */
    private tools.jackson.databind.JsonNode buildAnswerKey(ValidQuestionRow row) {
        if (row.questionType() != QuestionType.NUMERIC_FILL) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        // expectedAnswer: JSON string, preserved verbatim (no strip, no reparse).
        // requiredInputLength is a constant 4 (frontend input); roundingInstruction lives in the content.
        node.put("expectedAnswer", row.expectedAnswer());
        return node;
    }

    private List<QuestionOption> buildOptions(ValidQuestionRow row, Long versionId) {
        return switch (row.questionType()) {
            case SINGLE_CHOICE, MULTIPLE_CHOICE -> buildChoiceOptions(row, versionId);
            case TRUE_FALSE_MATRIX -> buildTrueFalseOptions(row, versionId);
            case NUMERIC_FILL -> List.of(); // no options
        };
    }

    private List<QuestionOption> buildChoiceOptions(ValidQuestionRow row, Long versionId) {
        List<QuestionOption> options = new ArrayList<>();
        int position = 0;
        for (String key : new String[] { "A", "B", "C", "D" }) {
            String text = row.options().get(key);
            if (text == null || text.isBlank()) {
                continue;
            }
            boolean correct = row.correctAnswers().contains(key);
            options.add(new QuestionOption(versionId, key, text, position, correct));
            position++;
        }
        return options;
    }

    private List<QuestionOption> buildTrueFalseOptions(ValidQuestionRow row, Long versionId) {
        List<QuestionOption> options = new ArrayList<>();
        int position = 0;
        for (String key : new String[] { "A", "B", "C", "D" }) {
            String text = row.statements().get(key);
            Boolean answer = row.statementAnswers().get(key);
            options.add(new QuestionOption(versionId, key, text, position,
                    Boolean.TRUE.equals(answer)));
            position++;
        }
        return options;
    }

    // ============================================================
    // Concurrent-duplicate constraint detection
    // ============================================================

    private static boolean isBankCodeUniqueViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (DUPLICATE_CONSTRAINT.equals(name)) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}
