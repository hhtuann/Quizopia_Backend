package com.quizopia.backend.question.ai;

import com.quizopia.backend.question.ai.provider.AiProviderException;
import com.quizopia.backend.question.ai.provider.AiProviderTurn;
import com.quizopia.backend.question.ai.provider.AiQuizProvider;
import com.quizopia.backend.question.application.MarkdownQuestionImportService;
import com.quizopia.backend.question.application.QuestionBankImportAccessGuard;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownError;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParseResult;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParser;
import com.quizopia.backend.question.dto.AiAuthorDiagnostic;
import com.quizopia.backend.question.dto.AiAuthorDocument;
import com.quizopia.backend.question.dto.AiAuthorMessage;
import com.quizopia.backend.question.dto.AiAuthorRequest;
import com.quizopia.backend.question.dto.AiAuthorResponse;
import com.quizopia.backend.question.dto.AiAuthoringStatusResponse;
import com.quizopia.backend.question.dto.AiDocumentExtractResponse;
import com.quizopia.backend.question.dto.RowError;
import com.quizopia.backend.question.exception.AiAuthoringErrorCode;
import com.quizopia.backend.question.exception.AiAuthoringException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Teacher-only AI-assisted Quiz Markdown authoring (Phase 5, issues #112/#113).
 *
 * <p><strong>Authorization</strong> — reuses the shared
 * {@link QuestionBankImportAccessGuard} (the same boundary as the Excel and
 * Markdown imports: active TEACHER role, QUESTION_CREATE permission, bank
 * creator equality, ACTIVE bank). It runs BEFORE request validation, file
 * reading or any provider call, so unauthorized callers never consume
 * provider quota or reach parsing code.
 *
 * <p><strong>Nothing persists.</strong> The candidate Markdown document is
 * validated with the authoritative {@link QuizMarkdownParser} plus the
 * existing {@link MarkdownQuestionImportService#bankContractErrors} bank
 * contract preflight and returned to the caller; persistence happens only
 * through the teacher's explicit {@code import-markdown} request.
 *
 * <p><strong>Bounded</strong> — instruction/markdown/history/document sizes
 * are capped, document text sent upstream is capped, and one bounded repair
 * attempt re-asks the provider when the first candidate is invalid.
 */
@Service
public class AiAuthoringService {

    private static final Logger log = LoggerFactory.getLogger(AiAuthoringService.class);

    private static final Set<String> HISTORY_ROLES = Set.of("user", "assistant");
    private static final int MAX_DOCUMENT_NAME_CHARS = 200;

    /**
     * Package-private so the env-gated real-provider smoke test exercises the
     * production prompt verbatim.
     */
    static final String SYSTEM_PROMPT = """
            You are Quizopia's quiz authoring assistant for teachers. You produce and revise Quiz Markdown documents.

            QUIZ MARKDOWN GRAMMAR (follow exactly):
            - Each question starts with a header line: Question <n> [<TYPE>]: <stem>. Numbering starts at 1 and increases by exactly 1 — no gaps, no duplicates.
            - TYPE is exactly one of: SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE_MATRIX, NUMERIC_FILL.
            - For SINGLE_CHOICE, MULTIPLE_CHOICE and TRUE_FALSE_MATRIX: exactly four option lines follow, labeled A., B., C., D. in order. A leading * (e.g. *A.) marks a correct option.
              * SINGLE_CHOICE: exactly ONE starred option.
              * MULTIPLE_CHOICE: at least TWO starred options.
              * TRUE_FALSE_MATRIX: each option is a statement; starred options are the TRUE statements.
            - For NUMERIC_FILL: no A-D options. Add one line: Expected answer: <token>. The token must be EXACTLY four characters and match ^-?[0-9]+([.][0-9]+)?$ — pad with decimals to reach four characters (e.g. 5.00, 42.0, -0.5, 0.75; never 5, 42 or -.5).
            - Optionally, AFTER the complete answer structure, one line: Explanation: <text>. A document may contain at most 200 questions.
            - The star in a correct option touches the letter with NO space and no bold: write *A. not * A., **A.** or A.*.
            - The stem may span multiple lines until the first option/answer marker. Inline $...$ and display $$...$$ math are allowed. Do not use Markdown headings, bold, lists or code fences around questions.

            EXAMPLE of one complete question:
            Question 1 [SINGLE_CHOICE]: Which sequence describes the TCP three-way handshake?
            A. SYN, ACK, FIN
            *B. SYN, SYN-ACK, ACK
            C. ACK, SYN, SYN-ACK
            D. FIN, ACK, FIN-ACK
            Explanation: The client sends SYN, the server answers SYN-ACK, the client confirms ACK.

            RULES:
            - When revising, return the COMPLETE document — every question including untouched ones — preserving the current questions unless the teacher's instruction says otherwise.
            - Ground questions in the provided document extracts when present; otherwise write pedagogically sound questions.
            - Reply in English with one or two short sentences describing what you did; put the document only in the markdown field.
            """;

    private final QuestionBankImportAccessGuard accessGuard;
    private final AiQuizProvider provider;
    private final AiDocumentExtractor documentExtractor;
    private final AiAuthoringProperties properties;
    /** Pure parser (stateless), instantiated directly like the domain intends. */
    private final QuizMarkdownParser markdownParser = new QuizMarkdownParser();

    /**
     * In-memory per-user authoring bucket (issue #113 review: the paid
     * provider call needs its own backpressure). Single-node MVP — a shared
     * store would be needed for multi-instance deployments.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.Deque<java.time.Instant>> authoringCalls =
            new java.util.concurrent.ConcurrentHashMap<>();

    public AiAuthoringService(QuestionBankImportAccessGuard accessGuard,
            AiQuizProvider provider,
            AiDocumentExtractor documentExtractor,
            AiAuthoringProperties properties) {
        this.accessGuard = accessGuard;
        this.provider = provider;
        this.documentExtractor = documentExtractor;
        this.properties = properties;
    }

    /** Feature availability for the bank owner (auth first: non-owners get 403/404). */
    public AiAuthoringStatusResponse status(Long userId, Long bankId) {
        accessGuard.requireImportAccess(userId, bankId);
        return new AiAuthoringStatusResponse(properties.enabled(), properties.available(), properties.model());
    }

    /** Extraction endpoint: authorize, then bounded extraction. No persistence. */
    public AiDocumentExtractResponse extractDocument(Long userId, Long bankId, MultipartFile file) {
        accessGuard.requireImportAccess(userId, bankId);
        if (!properties.enabled()) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_AUTHORING_DISABLED,
                    "AI-assisted authoring is disabled");
        }
        if (file == null) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_INVALID,
                    "Required file part is missing");
        }
        return documentExtractor.extract(file);
    }

    /**
     * Authoring endpoint: authorize, validate, call the provider, validate the
     * candidate with the authoritative parser + bank contract, optionally one
     * repair attempt. Never persists.
     */
    public AiAuthorResponse author(Long userId, Long bankId, AiAuthorRequest request) {
        accessGuard.requireImportAccess(userId, bankId);
        if (!properties.available()) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_AUTHORING_DISABLED,
                    "AI-assisted authoring is not available (disabled or not configured)");
        }
        validate(request);
        requireAuthoringQuota(userId);
        AiProviderResultHolder result = callProvider(buildTurns(request));
        String candidate = stripCodeFences(result.markdown());
        Validated validated = validateCandidate(candidate);

        if (!validated.valid() && properties.repairAttempts() > 0) {
            result = callProvider(buildRepairTurns(request, candidate, validated.diagnostics));
            candidate = stripCodeFences(result.markdown());
            validated = validateCandidate(candidate);
        }

        return new AiAuthorResponse(candidate, result.reply(), validated.valid(), validated.diagnostics);
    }

    /**
     * Per-user sliding-window quota on provider calls: at most
     * {@code maxAuthorRequestsPerWindow} within {@code authoringWindowSeconds}.
     * Buckets are pruned lazily on each call.
     */
    private void requireAuthoringQuota(Long userId) {
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant windowStart = now.minusSeconds(properties.authoringWindowSeconds());
        java.util.Deque<java.time.Instant> calls = authoringCalls.computeIfAbsent(userId, k ->
                new java.util.concurrent.ConcurrentLinkedDeque<>());
        while (true) {
            java.time.Instant oldest = calls.peekFirst();
            if (oldest == null || oldest.isAfter(windowStart)) {
                break;
            }
            calls.pollFirst();
        }
        if (calls.size() >= properties.maxAuthorRequestsPerWindow()) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_AUTHORING_RATE_LIMITED,
                    "Too many AI authoring requests — try again later");
        }
        calls.addLast(now);
    }

    // ------------------------------------------------------------------
    // Request validation (after authorization)
    // ------------------------------------------------------------------

    private void validate(AiAuthorRequest request) {
        if (request == null) {
            throw invalid("Request body must not be null");
        }
        String instruction = request.instruction();
        if (instruction == null || instruction.isBlank()) {
            throw invalid("instruction must not be blank");
        }
        if (instruction.length() > properties.maxInstructionChars()) {
            throw invalid("instruction exceeds " + properties.maxInstructionChars() + " characters");
        }
        String markdown = request.currentMarkdown();
        if (markdown != null && markdown.length() > properties.maxMarkdownChars()) {
            throw invalid("currentMarkdown exceeds " + properties.maxMarkdownChars() + " characters");
        }
        List<AiAuthorDocument> documents = request.documents();
        if (documents != null) {
            if (documents.size() > properties.maxDocuments()) {
                throw invalid("At most " + properties.maxDocuments() + " documents per request");
            }
            for (AiAuthorDocument document : documents) {
                if (document == null || document.name() == null || document.name().isBlank()
                        || document.name().length() > MAX_DOCUMENT_NAME_CHARS) {
                    throw invalid("Document name must be 1-" + MAX_DOCUMENT_NAME_CHARS + " characters");
                }
                if (document.text() == null) {
                    throw invalid("Document text must not be null");
                }
                if (document.text().length() > properties.maxExtractedChars()) {
                    throw invalid("Document " + document.name() + " exceeds "
                            + properties.maxExtractedChars() + " characters");
                }
            }
        }
        List<AiAuthorMessage> history = request.history();
        if (history != null) {
            if (history.size() > properties.maxHistoryMessages()) {
                throw invalid("history exceeds " + properties.maxHistoryMessages() + " messages");
            }
            for (AiAuthorMessage message : history) {
                if (message == null || !HISTORY_ROLES.contains(message.role())) {
                    throw invalid("history role must be 'user' or 'assistant'");
                }
                if (message.content() == null || message.content().isBlank()
                        || message.content().length() > properties.maxHistoryMessageChars()) {
                    throw invalid("history message must be 1-"
                            + properties.maxHistoryMessageChars() + " characters");
                }
            }
        }
    }

    private static AiAuthoringException invalid(String message) {
        return new AiAuthoringException(AiAuthoringErrorCode.AI_AUTHORING_REQUEST_INVALID, message);
    }

    // ------------------------------------------------------------------
    // Prompt construction
    // ------------------------------------------------------------------

    private List<AiProviderTurn> buildTurns(AiAuthorRequest request) {
        List<AiProviderTurn> turns = new ArrayList<>();
        turns.add(new AiProviderTurn("system", SYSTEM_PROMPT));
        if (request.history() != null) {
            for (AiAuthorMessage message : request.history()) {
                turns.add(new AiProviderTurn(message.role(), message.content()));
            }
        }
        turns.add(new AiProviderTurn("user", buildUserContent(request)));
        return turns;
    }

    private List<AiProviderTurn> buildRepairTurns(AiAuthorRequest request,
            String candidate, List<AiAuthorDiagnostic> diagnostics) {
        List<AiProviderTurn> turns = buildTurns(request);
        StringBuilder repair = new StringBuilder("Your previous candidate failed Quiz Markdown validation.\nDiagnostics:\n");
        for (AiAuthorDiagnostic diagnostic : diagnostics) {
            repair.append("- [").append(diagnostic.code()).append("] ").append(diagnostic.message()).append('\n');
        }
        repair.append("\nPrevious candidate:\n").append(candidate);
        repair.append("\n\nReturn the corrected COMPLETE document as JSON. Fix every diagnostic.");
        turns.add(new AiProviderTurn("user", repair.toString()));
        return turns;
    }

    private String buildUserContent(AiAuthorRequest request) {
        StringBuilder content = new StringBuilder(request.instruction());

        content.append("\n\nCurrent editor Markdown:\n");
        String markdown = request.currentMarkdown();
        content.append(markdown == null || markdown.isBlank() ? "(empty — create a new document)" : markdown);

        if (request.documents() != null && !request.documents().isEmpty()) {
            content.append("\n\nDocument extracts:\n");
            int budget = properties.maxTotalDocumentChars();
            for (AiAuthorDocument document : request.documents()) {
                if (budget <= 0) {
                    content.append("### ").append(document.name()).append("\n(omitted: document text budget exhausted)\n");
                    continue;
                }
                String text = document.text();
                boolean truncated = text.length() > budget;
                if (truncated) {
                    text = text.substring(0, budget);
                }
                budget -= text.length();
                content.append("### ").append(document.name())
                        .append(truncated ? " (truncated)\n" : "\n")
                        .append(text).append('\n');
            }
        }
        return content.toString();
    }

    // ------------------------------------------------------------------
    // Candidate validation (authoritative parser + bank contract)
    // ------------------------------------------------------------------

    private Validated validateCandidate(String candidate) {
        QuizMarkdownParseResult parsed = markdownParser.parse(candidate);
        if (!parsed.isValid()) {
            List<AiAuthorDiagnostic> diagnostics = parsed.errors().stream()
                    .map(AiAuthoringService::toDiagnostic)
                    .toList();
            return new Validated(false, diagnostics);
        }
        var content = parsed.content().orElseThrow();
        List<RowError> contract = MarkdownQuestionImportService.bankContractErrors(content);
        if (!contract.isEmpty()) {
            List<AiAuthorDiagnostic> diagnostics = contract.stream()
                    .map(AiAuthoringService::toDiagnostic)
                    .toList();
            return new Validated(false, diagnostics);
        }
        // Mirrors the import hard cap so a "valid" candidate never
        // hard-fails later at the explicit import step (issue #113 review).
        if (content.questions().size() > MarkdownQuestionImportService.MAX_MARKDOWN_QUESTIONS) {
            return new Validated(false, List.of(new AiAuthorDiagnostic(
                    "QUESTION_IMPORT_MARKDOWN_TOO_MANY_QUESTIONS", null, 0, 0,
                    "Document has " + content.questions().size() + " questions; the bank import accepts at most "
                            + MarkdownQuestionImportService.MAX_MARKDOWN_QUESTIONS)));
        }
        return new Validated(true, List.of());
    }

    private static AiAuthorDiagnostic toDiagnostic(QuizMarkdownError error) {
        return new AiAuthorDiagnostic(error.code(), error.questionNumber(),
                error.line(), error.column(), error.message());
    }

    private static AiAuthorDiagnostic toDiagnostic(RowError error) {
        return new AiAuthorDiagnostic(error.code(), error.rowNumber(),
                error.line() == null ? 0 : error.line(),
                error.column() == null ? 0 : error.column(),
                error.message());
    }

    /** Tolerates models that wrap the document in a ```markdown fence. */
    static String stripCodeFences(String markdown) {
        if (markdown == null) {
            return "";
        }
        String stripped = markdown.strip();
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            if (firstNewline > 0) {
                stripped = stripped.substring(firstNewline + 1);
            }
            int lastFence = stripped.lastIndexOf("```");
            if (lastFence >= 0) {
                stripped = stripped.substring(0, lastFence);
            }
        }
        return stripped.stripTrailing();
    }

    // ------------------------------------------------------------------
    // Provider call + error mapping
    // ------------------------------------------------------------------

    private AiProviderResultHolder callProvider(List<AiProviderTurn> turns) {
        try {
            var result = provider.complete(turns);
            return new AiProviderResultHolder(result.reply(), result.markdown());
        } catch (AiProviderException e) {
            log.warn("AI authoring provider call failed: kind={} message={}",
                    e.getKind(), e.getMessage());
            throw switch (e.getKind()) {
                case RATE_LIMITED -> new AiAuthoringException(
                        AiAuthoringErrorCode.AI_PROVIDER_RATE_LIMITED,
                        "The AI provider is rate limiting requests — try again shortly");
                case TIMEOUT -> new AiAuthoringException(
                        AiAuthoringErrorCode.AI_PROVIDER_TIMEOUT,
                        "The AI provider timed out — try again");
                case UPSTREAM_ERROR, PROTOCOL -> new AiAuthoringException(
                        AiAuthoringErrorCode.AI_PROVIDER_ERROR,
                        "The AI provider request failed — try again");
            };
        }
    }

    /** Local pair so the service never leaks provider types upward. */
    private record AiProviderResultHolder(String reply, String markdown) {
    }

    private record Validated(boolean valid, List<AiAuthorDiagnostic> diagnostics) {
    }
}
