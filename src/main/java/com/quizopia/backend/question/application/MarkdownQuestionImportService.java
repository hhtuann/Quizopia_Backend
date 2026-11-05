package com.quizopia.backend.question.application;

import com.quizopia.backend.common.BusinessCodes;
import com.quizopia.backend.notification.application.NotificationService;
import com.quizopia.backend.notification.domain.model.NotificationType;
import com.quizopia.backend.question.domain.markdown.QuizContent;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownError;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParseResult;
import com.quizopia.backend.question.domain.markdown.QuizQuestion;
import com.quizopia.backend.question.domain.model.Question;
import com.quizopia.backend.question.domain.model.QuestionBank;
import com.quizopia.backend.question.domain.model.QuestionDifficulty;
import com.quizopia.backend.question.domain.model.QuestionOption;
import com.quizopia.backend.question.domain.model.QuestionStatus;
import com.quizopia.backend.question.domain.model.QuestionType;
import com.quizopia.backend.question.domain.model.QuestionVersion;
import com.quizopia.backend.question.dto.ImportResponse;
import com.quizopia.backend.question.dto.RowError;
import com.quizopia.backend.question.exception.QuestionErrorCode;
import com.quizopia.backend.question.exception.QuestionException;
import com.quizopia.backend.question.repository.QuestionOptionRepository;
import com.quizopia.backend.question.repository.QuestionRepository;
import com.quizopia.backend.question.repository.QuestionVersionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Application service for the Teacher-only Quiz Markdown Question Bank import
 * (P2-C, issues #18 and #10).
 *
 * <p><strong>Authorization</strong> — deny by default via
 * {@link QuestionBankImportAccessGuard}: active TEACHER role,
 * QUESTION_CREATE permission, bank creator equality, Phase 1 workspace
 * boundary and ACTIVE bank state. Re-authorized inside the persistence
 * transaction; the controller also runs the same checks as an early-out
 * before parsing.
 *
 * <p><strong>Bank contract preflight (issue #10 resolution)</strong> — the
 * pure Markdown parser deliberately keeps the donor grammar (MULTIPLE_CHOICE
 * {@code >=1} correct; numeric tokens like {@code -.50}); this adapter
 * additionally enforces the established Question Bank / Exam publication
 * contract before anything is persisted:
 * <ul>
 *   <li>MULTIPLE_CHOICE requires {@code >=2} correct options (exactly one
 *       correct is SINGLE_CHOICE territory; editing and publishing both
 *       reject it today);</li>
 *   <li>NUMERIC_FILL answer must match {@code ^-?[0-9]+([.][0-9]+)?$}
 *       (exactly 4 characters) — identical to question editing, exam
 *       publication and grading. {@code -.50} is rejected with an actionable
 *       diagnostic; the token is never silently rewritten to {@code -0.50}.</li>
 * </ul>
 *
 * <p><strong>Atomicity</strong> — a Markdown document is one authored
 * artifact, unlike a row-oriented Excel workbook. The import is
 * <em>all-or-nothing</em>: any parse error or bank-contract violation rejects
 * the whole document with per-question diagnostics and persists nothing
 * (HTTP 200 with {@code importedRows=0} and the error list, mirroring the
 * {@link ImportResponse} partial-success transport shape). Only a hard
 * precondition failure (auth, size, count) is a 4xx {@code ApiError}.
 *
 * <p><strong>Persistence conventions</strong> — identical to the Excel
 * import: {@code QU}-prefixed generated codes, ACTIVE status, version 1,
 * MEDIUM default difficulty, 1.00 default points, verbatim answer key for
 * NUMERIC_FILL, {@code {}} metadata, IMPORT_COMPLETED notification, and the
 * {@code uk_questions_bank_code_ci} concurrent-duplicate mapping to
 * QUESTION_IMPORT_DUPLICATE_CODE with full rollback.
 */
@Service
public class MarkdownQuestionImportService {

    /** Canonical NUMERIC_FILL answer contract shared by edit/publish/grading. */
    static final Pattern BANK_NUMERIC_PATTERN = Pattern.compile("^-?[0-9]+([.][0-9]+)?$");

    /** Hard cap on the Markdown source length (256 KiB characters). */
    public static final int MAX_MARKDOWN_CHARS = 262_144;

    /** Hard cap on questions per imported Markdown document. */
    public static final int MAX_MARKDOWN_QUESTIONS = 200;

    private final QuestionBankImportAccessGuard accessGuard;
    private final QuestionRepository questionRepository;
    private final QuestionVersionRepository versionRepository;
    private final QuestionOptionRepository optionRepository;
    private final NotificationService notificationService;

    public MarkdownQuestionImportService(QuestionBankImportAccessGuard accessGuard,
            QuestionRepository questionRepository,
            QuestionVersionRepository versionRepository,
            QuestionOptionRepository optionRepository,
            NotificationService notificationService) {
        this.accessGuard = accessGuard;
        this.questionRepository = questionRepository;
        this.versionRepository = versionRepository;
        this.optionRepository = optionRepository;
        this.notificationService = notificationService;
    }

    /**
     * Read-only preflight authorization (NOT {@code @Transactional}) so the
     * controller can reject an unauthorized caller before parsing. The
     * transactional {@link #importParsedMarkdown} re-runs the same checks.
     */
    public void authorizeImportAccess(Long userId, Long bankId) {
        accessGuard.requireImportAccess(userId, bankId);
    }

    /**
     * Parses-are-done orchestration entry: validates the already-parsed
     * Markdown result against the bank contract and, when fully valid,
     * persists every question in one transaction.
     *
     * <p>Parse errors are mapped to {@link RowError}s (rowNumber = question
     * number, 0 for document-level errors) and returned without persisting.
     */
    @Transactional
    public ImportResponse importParsedMarkdown(Long userId, Long bankId, QuizMarkdownParseResult parsed) {
        // --- Authorization re-check inside the transaction (deny by default) ---
        QuestionBank bank = accessGuard.requireImportAccess(userId, bankId);

        if (!parsed.isValid()) {
            return errorResponse(parsed.errors().stream()
                    .map(MarkdownQuestionImportService::toRowError)
                    .toList());
        }

        QuizContent content = parsed.content().orElseThrow();
        if (content.questions().size() > MAX_MARKDOWN_QUESTIONS) {
            throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_MARKDOWN_TOO_MANY_QUESTIONS);
        }

        List<RowError> contractErrors = bankContractErrors(content);
        if (!contractErrors.isEmpty()) {
            return errorResponse(contractErrors);
        }

        // --- Persist (all-or-nothing; codes auto-generated like Excel) ---
        try {
            for (QuizQuestion question : content.questions()) {
                persistQuestion(question, bank.getId(), userId);
            }
            questionRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            if (QuestionImportService.isBankCodeUniqueViolation(ex)) {
                throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_DUPLICATE_CODE);
            }
            throw ex;
        }

        int total = content.questions().size();
        notificationService.create(userId, NotificationType.IMPORT_COMPLETED,
                "Markdown import completed",
                total + "/" + total + " questions imported",
                "/question-banks/" + bankId);

        return new ImportResponse(total, total, 0, List.of());
    }

    /**
     * Bank-contract preflight (issue #10): pure function over parser-valid
     * content. Returns per-question {@link RowError}s for constructs the
     * Question Bank editing / Exam publication contract rejects.
     */
    public static List<RowError> bankContractErrors(QuizContent content) {
        List<RowError> errors = new ArrayList<>();
        for (QuizQuestion question : content.questions()) {
            if (question.type() == QuestionType.MULTIPLE_CHOICE) {
                long correct = question.options().stream().filter(o -> o.correct()).count();
                if (correct < 2) {
                    errors.add(new RowError(question.number(), null, "correctAnswers",
                            "MULTIPLE_CHOICE_MIN_TWO_CORRECT",
                            "Question " + question.number()
                                    + ": MULTIPLE_CHOICE requires at least 2 correct options — mark them with '*'"
                                    + " (single-correct questions must use SINGLE_CHOICE)."));
                }
            } else if (question.type() == QuestionType.NUMERIC_FILL) {
                String token = question.numericAnswer();
                if (!BANK_NUMERIC_PATTERN.matcher(token).matches()) {
                    errors.add(new RowError(question.number(), null, "expectedAnswer",
                            "NUMERIC_ANSWER_BANK_CONTRACT",
                            "Question " + question.number() + ": numeric answer '" + token
                                    + "' is not accepted by the Question Bank contract — write a digit before the"
                                    + " decimal point (e.g. '-0.5', not '-.5'). The answer is stored verbatim and is"
                                    + " never rewritten."));
                }
            }
        }
        return errors;
    }

    private static RowError toRowError(QuizMarkdownError error) {
        return new RowError(
                error.questionNumber() == null ? 0 : error.questionNumber(),
                null,
                null,
                error.code(),
                error.message());
    }

    /** Sorted, nothing-persisted {@link ImportResponse} for a rejected document. */
    private static ImportResponse errorResponse(List<RowError> rowErrors) {
        List<RowError> sorted = new ArrayList<>(rowErrors);
        sorted.sort(Comparator.comparingInt(RowError::rowNumber));
        int invalidRows = (int) sorted.stream().map(RowError::rowNumber).filter(n -> n > 0).distinct().count();
        return new ImportResponse(0, 0, invalidRows, sorted);
    }

    // ============================================================
    // Entity mapping + persistence (mirrors QuestionImportService)
    // ============================================================

    private void persistQuestion(QuizQuestion question, Long bankId, Long userId) {
        String code = BusinessCodes.readableCode("QU", 8,
                c -> questionRepository.existsByQuestionBankIdAndCodeIgnoreCase(bankId, c));
        Question entity = new Question(bankId, code, userId);
        entity.setStatus(QuestionStatus.ACTIVE);
        questionRepository.saveAndFlush(entity);

        QuestionVersion version = new QuestionVersion(
                entity.getId(),
                1,
                question.type(),
                question.stemMarkdown(),
                question.explanationMarkdown(),
                QuestionDifficulty.MEDIUM,
                BigDecimal.ONE,
                buildAnswerKey(question),
                JsonNodeFactory.instance.objectNode(), // metadata = {}
                userId);
        versionRepository.saveAndFlush(version);

        List<QuestionOption> options = buildOptions(question, version.getId());
        if (!options.isEmpty()) {
            optionRepository.saveAll(options);
        }
    }

    /** NUMERIC_FILL only; the token is stored verbatim (prevalidated). */
    private static JsonNode buildAnswerKey(QuizQuestion question) {
        if (question.type() != QuestionType.NUMERIC_FILL) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("expectedAnswer", question.numericAnswer());
        return node;
    }

    private static List<QuestionOption> buildOptions(QuizQuestion question, Long versionId) {
        if (question.type() == QuestionType.NUMERIC_FILL) {
            return List.of();
        }
        List<QuestionOption> options = new ArrayList<>(question.options().size());
        int position = 0;
        for (var option : question.options()) {
            options.add(new QuestionOption(versionId, option.label().name(), option.markdown(),
                    position++, option.correct()));
        }
        return options;
    }
}
