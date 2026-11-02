package com.quizopia.backend.exam.application;

import com.quizopia.backend.academic.domain.model.AcademicStatus;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.domain.model.TeacherProfile;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.academic.repository.TeacherProfileRepository;
import com.quizopia.backend.common.BusinessCodes;
import com.quizopia.backend.exam.domain.model.*;
import com.quizopia.backend.exam.dto.*;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.exam.repository.*;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.question.domain.model.Question;
import com.quizopia.backend.question.domain.model.QuestionBank;
import com.quizopia.backend.question.domain.model.QuestionBankStatus;
import com.quizopia.backend.question.domain.model.QuestionDifficulty;
import com.quizopia.backend.question.domain.model.QuestionOption;
import com.quizopia.backend.question.domain.model.QuestionStatus;
import com.quizopia.backend.question.domain.model.QuestionType;
import com.quizopia.backend.question.domain.model.QuestionVersion;
import com.quizopia.backend.question.dto.PageResponse;
import com.quizopia.backend.question.dto.SubjectSummary;
import com.quizopia.backend.question.repository.QuestionBankRepository;
import com.quizopia.backend.question.repository.QuestionOptionRepository;
import com.quizopia.backend.question.repository.QuestionRepository;
import com.quizopia.backend.question.repository.QuestionVersionRepository;
import com.quizopia.backend.workspace.application.OrganizationWorkspaceResolver;
import com.quizopia.backend.workspace.application.WorkspaceAccessService;
import com.quizopia.backend.workspace.application.WorkspaceProvisioningService;
import com.quizopia.backend.workspace.domain.model.Workspace;
import com.quizopia.backend.workspace.domain.model.WorkspaceRole;
import com.quizopia.backend.workspace.domain.model.WorkspaceType;
import com.quizopia.backend.workspace.repository.OrganizationRepository;
import com.quizopia.backend.workspace.repository.WorkspaceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Application service for the Exam API — one Exam = one immutable published
 * content set (Task B redesign, issue #2 / ADR-001 §2.4):
 * <ul>
 * <li>POST /api/exams — create DRAFT exam (personal or organization workspace)</li>
 * <li>GET /api/exams/my — creator-scoped list</li>
 * <li>GET /api/exams/{examId} — management view (composition + metadata)</li>
 * <li>PUT /api/exams/{examId}/composition — DRAFT-only composition replace</li>
 * <li>POST /api/exams/{examId}/publish — one-way DRAFT → PUBLISHED</li>
 * <li>POST /api/exams/{examId}/duplicate — deep copy into a new DRAFT exam</li>
 * <li>POST /api/exams/{examId}/archive — PUBLISHED → ARCHIVED (session guard)</li>
 * </ul>
 *
 * <p>Authorization (deny by default): effective permission (DB) + workspace
 * membership + creator-or-workspace-admin ownership. No JWT-claim authority,
 * no role hierarchy.
 */
@Service
public class ExamService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORT_ALLOWLIST = Set.of("createdAt", "title", "code", "status");
    private static final String CODE_CONFLICT_CONSTRAINT = "uk_exams_workspace_creator_code_ci";

    private final ExamAuthorizationService auth;
    private final SubjectRepository subjectRepository;
    private final ExamPurposeRepository purposeRepository;
    private final ExamRepository examRepository;
    private final ExamSectionRepository sectionRepository;
    private final ExamQuestionRepository questionRepository;
    private final ExamQuestionOptionRepository optionRepository;
    private final ExamSessionRepository sessionRepository;
    private final QuestionRepository sourceQuestionRepository;
    private final QuestionBankRepository sourceBankRepository;
    private final QuestionVersionRepository sourceVersionRepository;
    private final QuestionOptionRepository sourceOptionRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final OrganizationRepository organizationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final OrganizationWorkspaceResolver organizationWorkspaceResolver;
    private final WorkspaceAccessService workspaceAccessService;
    private final WorkspaceProvisioningService workspaceProvisioningService;
    private final UserRepository userRepository;

    public ExamService(ExamAuthorizationService auth,
            SubjectRepository subjectRepository,
            ExamPurposeRepository purposeRepository,
            ExamRepository examRepository,
            ExamSectionRepository sectionRepository,
            ExamQuestionRepository questionRepository,
            ExamQuestionOptionRepository optionRepository,
            ExamSessionRepository sessionRepository,
            QuestionRepository sourceQuestionRepository,
            QuestionBankRepository sourceBankRepository,
            QuestionVersionRepository sourceVersionRepository,
            QuestionOptionRepository sourceOptionRepository,
            TeacherProfileRepository teacherProfileRepository,
            OrganizationRepository organizationRepository,
            WorkspaceRepository workspaceRepository,
            OrganizationWorkspaceResolver organizationWorkspaceResolver,
            WorkspaceAccessService workspaceAccessService,
            WorkspaceProvisioningService workspaceProvisioningService,
            UserRepository userRepository) {
        this.auth = auth;
        this.subjectRepository = subjectRepository;
        this.purposeRepository = purposeRepository;
        this.examRepository = examRepository;
        this.sectionRepository = sectionRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.sessionRepository = sessionRepository;
        this.sourceQuestionRepository = sourceQuestionRepository;
        this.sourceBankRepository = sourceBankRepository;
        this.sourceVersionRepository = sourceVersionRepository;
        this.sourceOptionRepository = sourceOptionRepository;
        this.teacherProfileRepository = teacherProfileRepository;
        this.organizationRepository = organizationRepository;
        this.workspaceRepository = workspaceRepository;
        this.organizationWorkspaceResolver = organizationWorkspaceResolver;
        this.workspaceAccessService = workspaceAccessService;
        this.workspaceProvisioningService = workspaceProvisioningService;
        this.userRepository = userRepository;
    }

    // ============================================================
    // GET /api/exam-purposes
    // ============================================================

    @Transactional(readOnly = true)
    public List<ExamPurposeResponse> listPurposes(Long userId) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_PURPOSE_READ");
        return purposeRepository.findAllBySchoolIdOrderByPositionAsc(profile.getSchoolId()).stream()
                .map(p -> new ExamPurposeResponse(p.getId(), p.getCode(), p.getTitle(), p.getPosition()))
                .toList();
    }

    // ============================================================
    // POST /api/exams
    // ============================================================

    @Transactional
    public ExamListItem createExam(Long userId, CreateExamRequest request) {
        auth.requireUserWithPermission(userId, "EXAM_CREATE");

        Workspace workspace = resolveAuthoringWorkspace(userId, request.workspaceId());
        TeacherProfile profile = teacherProfileRepository.findByUserId(userId).orElse(null);

        Subject subject = null;
        if (workspace.getWorkspaceType() == WorkspaceType.ORGANIZATION) {
            if (request.subjectId() == null) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR,
                        "subjectId is required for organization workspace exams");
            }
            Long schoolId = requireOrganizationSchoolId(workspace);
            subject = subjectRepository.findById(request.subjectId())
                    .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_SUBJECT_NOT_FOUND));
            if (!schoolId.equals(subject.getSchoolId())) {
                throw new ExamException(ExamErrorCode.EXAM_SUBJECT_SCHOOL_MISMATCH);
            }
            if (subject.getStatus() != AcademicStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_SUBJECT_NOT_FOUND);
            }
        } else if (request.subjectId() != null) {
            throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR,
                    "subjectId is not supported for personal workspace exams");
        }

        // Purpose (nullable) — org workspaces only; must belong to the org school.
        ExamPurpose purpose = null;
        if (request.purposeId() != null) {
            if (workspace.getWorkspaceType() != WorkspaceType.ORGANIZATION) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR,
                        "purposeId is not supported for personal workspace exams");
            }
            Long schoolId = requireOrganizationSchoolId(workspace);
            purpose = purposeRepository.findById(request.purposeId())
                    .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_PURPOSE_NOT_FOUND));
            if (!schoolId.equals(purpose.getSchoolId())) {
                throw new ExamException(ExamErrorCode.EXAM_PURPOSE_SCHOOL_MISMATCH);
            }
        }

        Long workspaceId = workspace.getId();
        String code = (request.code() == null || request.code().isBlank())
                ? BusinessCodes.readableCode("EX", 8,
                        c -> examRepository.existsByWorkspaceIdAndCreatedByUserUserIdAndCodeIgnoreCase(
                                workspaceId, userId, c))
                : request.code().trim();
        Exam exam = new Exam(workspaceId, userId,
                subject != null ? subject.getSchoolId() : null,
                subject != null ? subject.getId() : null,
                profile != null ? profile.getId() : null,
                code, request.title());
        exam.setDescription(request.description());
        if (purpose != null) {
            exam.setPurposeId(purpose.getId());
        }

        try {
            exam = examRepository.saveAndFlush(exam);
        } catch (DataIntegrityViolationException ex) {
            if (isCodeConflict(ex)) {
                throw new ExamException(ExamErrorCode.EXAM_CODE_CONFLICT);
            }
            throw ex;
        }

        return new ExamListItem(
                exam.getId(), exam.getCode(), exam.getTitle(),
                subject != null ? new SubjectSummary(subject.getId(), subject.getCode(), subject.getName()) : null,
                purpose != null ? new ExamPurposeSummary(purpose.getId(), purpose.getCode(), purpose.getTitle()) : null,
                exam.getStatus().name(), 0L, exam.getTotalPoints(), exam.getPublishedAt(), exam.getCreatedAt());
    }

    // ============================================================
    // GET /api/exams/my
    // ============================================================

    @SuppressWarnings("null")
    @Transactional(readOnly = true)
    public PageResponse<ExamListItem> listMyExams(
            Long userId, String search, Long subjectId, String statusStr,
            int page, int size, String sort) {

        auth.requireUserWithPermission(userId, "EXAM_READ");

        ExamStatus statusFilter = parseStatusFilter(statusStr);
        PageRequest pageable = safePageable(page, size, sort);

        String trimmedSearch = (search == null || search.isBlank()) ? null : search.trim();

        // P0-2 (issue #4): only exams in workspaces the user may still access
        // (personal + ACTIVE-membership organizations) are listed — revoked
        // creators' org exams disappear from their list.
        List<Long> accessibleWorkspaceIds = workspaceAccessService.accessibleWorkspaceIds(userId);
        if (accessibleWorkspaceIds.isEmpty()) {
            return new PageResponse<>(List.of(), 0, Math.max(size, 1), 0, 0, pageable.getSort().toString());
        }

        Page<Exam> exams = examRepository.findCreatedByUser(userId, trimmedSearch, subjectId, statusFilter,
                accessibleWorkspaceIds, pageable);

        Map<Long, SubjectSummary> subjectMap = batchSubjects(exams.getContent());
        Map<Long, ExamPurposeSummary> purposeMap = batchPurposes(exams.getContent());
        Map<Long, Long> questionCounts = batchQuestionCounts(exams.getContent());

        List<ExamListItem> items = exams.getContent().stream()
                .map(e -> new ExamListItem(
                        e.getId(), e.getCode(), e.getTitle(),
                        subjectMap.get(e.getSubjectId()),
                        e.getPurposeId() != null ? purposeMap.get(e.getPurposeId()) : null,
                        e.getStatus().name(),
                        questionCounts.getOrDefault(e.getId(), 0L),
                        e.getTotalPoints(),
                        e.getPublishedAt(),
                        e.getCreatedAt()))
                .toList();

        return new PageResponse<>(items, exams.getNumber(), exams.getSize(),
                exams.getTotalElements(), exams.getTotalPages(), pageable.getSort().toString());
    }

    // ============================================================
    // GET /api/exams/{examId}
    // ============================================================

    @Transactional(readOnly = true)
    public TeacherExamEditorResponse getExamDetail(Long userId, Long examId) {
        auth.requireUserWithPermission(userId, "EXAM_READ");
        Exam exam = requireViewableExam(userId, examId);
        return buildEditorResponse(exam);
    }

    // ============================================================
    // PUT /api/exams/{examId}/composition (legacy: /draft/composition)
    // ============================================================

    @Transactional
    public TeacherExamEditorResponse updateDraftComposition(Long userId, Long examId,
            UpdateDraftCompositionRequest request) {
        auth.requireUserWithPermission(userId, "EXAM_UPDATE");

        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        requireManageableExam(userId, exam);

        if (!exam.isDraft()) {
            throw new ExamException(ExamErrorCode.EXAM_NOT_DRAFT);
        }

        // Optimistic token: the JPA @Version of the exam row.
        if (!exam.getVersion().equals(request.expectedExamVersion())) {
            throw new ExamException(ExamErrorCode.EXAM_CONCURRENT_MODIFICATION);
        }

        validateCompositionStructure(request);

        List<StagedSection> staged = resolveAndStageSources(request, exam);

        replaceComposition(exam.getId(), staged);

        exam.updateDraftSettings(request.durationMinutes(), request.instructions());
        exam.touchComposition(); // always bump the optimistic revision on edits
        examRepository.saveAndFlush(exam);

        return buildEditorResponse(exam);
    }

    // ============================================================
    // POST /api/exams/{examId}/publish
    // ============================================================

    @SuppressWarnings("null")
    @Transactional
    public PublishedExamSummary publishExam(Long userId, Long examId, PublishExamRequest request) {
        auth.requireUserWithPermission(userId, "EXAM_PUBLISH");

        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        requireManageableExam(userId, exam);

        if (exam.getStatus() == ExamStatus.PUBLISHED || exam.getStatus() == ExamStatus.ARCHIVED) {
            throw new ExamException(ExamErrorCode.EXAM_PUBLISH_CONFLICT);
        }
        if (request != null && request.expectedExamVersion() != null
                && !request.expectedExamVersion().equals(exam.getVersion())) {
            throw new ExamException(ExamErrorCode.EXAM_PUBLISH_CONFLICT);
        }

        List<ExamSection> sections = sectionRepository.findAllByExamIdOrderByPositionAsc(examId);
        if (sections.isEmpty()) {
            throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
        }
        List<Long> sectionIds = sections.stream().map(ExamSection::getId).toList();
        List<ExamQuestion> questions = questionRepository
                .findAllByExamSectionIdInOrderByExamSectionIdAscPositionAsc(sectionIds);
        Map<Long, List<ExamQuestion>> questionsBySection = questions.stream()
                .collect(Collectors.groupingBy(ExamQuestion::getExamSectionId));
        for (ExamSection s : sections) {
            if (questionsBySection.getOrDefault(s.getId(), List.of()).isEmpty()) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
        }
        List<Long> questionIds = questions.stream().map(ExamQuestion::getId).toList();

        // Batch-load pinned QuestionVersions + source Questions/Banks + pinned options.
        List<Long> pinnedVersionIds = questions.stream().map(ExamQuestion::getSourceQuestionVersionId).distinct()
                .toList();
        Map<Long, QuestionVersion> pinnedVersions = sourceVersionRepository.findAllById(pinnedVersionIds).stream()
                .collect(Collectors.toMap(QuestionVersion::getId, v -> v));
        Set<Long> sourceQuestionIdSet = questions.stream().map(ExamQuestion::getSourceQuestionId)
                .collect(Collectors.toSet());
        Map<Long, Question> sourceQuestions = sourceQuestionRepository.findAllById(sourceQuestionIdSet).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        Set<Long> bankIds = sourceQuestions.values().stream().map(Question::getQuestionBankId)
                .collect(Collectors.toSet());
        Map<Long, QuestionBank> sourceBanks = sourceBankRepository.findAllById(bankIds).stream()
                .collect(Collectors.toMap(QuestionBank::getId, b -> b));
        Map<Long, List<QuestionOption>> pinnedOptionsByVersion = sourceOptionRepository
                .findAllByQuestionVersionIdInOrderByQuestionVersionIdAscPositionAsc(pinnedVersionIds).stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionVersionId));

        BigDecimal totalPoints = BigDecimal.ZERO;
        for (ExamQuestion eq : questions) {
            QuestionVersion pv = pinnedVersions.get(eq.getSourceQuestionVersionId());
            Question sq = sourceQuestions.get(eq.getSourceQuestionId());
            if (pv == null || sq == null) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
            if (!pv.getQuestionId().equals(sq.getId())) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
            if (sq.getStatus() != QuestionStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
            QuestionBank bank = sourceBanks.get(sq.getQuestionBankId());
            if (bank == null || bank.getStatus() != QuestionBankStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
            if (!bank.getWorkspaceId().equals(exam.getWorkspaceId())
                    || !Objects.equals(bank.getSubjectId(), exam.getSubjectId())) {
                throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED);
            }
            eq.setDifficulty(pv.getDifficulty());
            eq.setExplanation(pv.getExplanation());
            eq.setAnswerKey(deepCopy(pv.getAnswerKey()));
            eq.setMetadata(deepCopy(pv.getMetadata()));
            validatePublishedQuestionType(pv.getQuestionType(),
                    pinnedOptionsByVersion.getOrDefault(pv.getId(), List.of()), pv.getAnswerKey());
            totalPoints = totalPoints.add(eq.getDefaultPoints());
        }
        if (totalPoints.signum() <= 0) {
            throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
        }
        questionRepository.flush();

        // Replace options from the pinned options (idempotent refresh).
        if (!questionIds.isEmpty()) {
            optionRepository.deleteAllByExamQuestionIdIn(questionIds);
            optionRepository.flush();
        }
        for (ExamQuestion eq : questions) {
            List<QuestionOption> pinnedOpts = pinnedOptionsByVersion.getOrDefault(eq.getSourceQuestionVersionId(),
                    List.of());
            if (!pinnedOpts.isEmpty()) {
                optionRepository.saveAll(pinnedOpts.stream()
                        .map(po -> new ExamQuestionOption(eq.getId(), po.getOptionKey(), po.getContent(),
                                po.getIsCorrect(), po.getPosition()))
                        .toList());
            }
        }
        optionRepository.flush();

        Instant publishedAt = Instant.now();
        exam.markPublished(publishedAt, totalPoints);
        examRepository.saveAndFlush(exam);

        return new PublishedExamSummary(exam.getId(), exam.getStatus().name(), publishedAt, totalPoints,
                questions.size(), exam.getDurationMinutes());
    }

    // ============================================================
    // POST /api/exams/{examId}/duplicate
    // ============================================================

    /**
     * Deep-copies the exam (sections/questions/options + settings) into a
     * brand-new DRAFT exam with new ids and {@code sourceExamId} provenance.
     * No sessions/attempts/results are copied. Pinned question provenance is
     * preserved verbatim.
     */
    @Transactional
    public ExamListItem duplicateExam(Long userId, Long examId) {
        auth.requireUserWithPermission(userId, "EXAM_CREATE");

        Exam source = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        requireManageableExam(userId, source);

        String code = BusinessCodes.readableCode("EX", 8,
                c -> examRepository.existsByWorkspaceIdAndCreatedByUserUserIdAndCodeIgnoreCase(
                        source.getWorkspaceId(), userId, c));
        Exam copy = new Exam(source.getWorkspaceId(), userId, source.getSchoolId(), source.getSubjectId(),
                source.getOwnerTeacherId(), code, source.getTitle());
        copy.setDescription(source.getDescription());
        copy.setPurposeId(source.getPurposeId());
        copy.updateDraftSettings(source.getDurationMinutes(), source.getInstructions());
        copy.copyGradingPolicyFrom(source);
        copy.markAsDuplicateOf(source.getId());
        try {
            copy = examRepository.saveAndFlush(copy);
        } catch (DataIntegrityViolationException ex) {
            if (isCodeConflict(ex)) {
                throw new ExamException(ExamErrorCode.EXAM_CODE_CONFLICT);
            }
            throw ex;
        }

        deepCopyComposition(source.getId(), copy.getId());

        Subject subject = source.getSubjectId() != null
                ? subjectRepository.findById(source.getSubjectId()).orElse(null) : null;
        return new ExamListItem(copy.getId(), copy.getCode(), copy.getTitle(),
                subject != null ? new SubjectSummary(subject.getId(), subject.getCode(), subject.getName()) : null,
                null, copy.getStatus().name(), 0L, copy.getTotalPoints(), copy.getPublishedAt(),
                copy.getCreatedAt());
    }

    // ============================================================
    // POST /api/exams/{examId}/archive
    // ============================================================

    /**
     * PUBLISHED → ARCHIVED. Guarded: rejected while any session of the exam is
     * SCHEDULED or OPEN (close/cancel them first). Historical sessions,
     * attempts and results are preserved untouched.
     */
    @Transactional
    public TeacherExamEditorResponse archiveExam(Long userId, Long examId) {
        auth.requireUserWithPermission(userId, "EXAM_UPDATE");

        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        requireManageableExam(userId, exam);

        if (exam.getStatus() == ExamStatus.DRAFT) {
            throw new ExamException(ExamErrorCode.EXAM_NOT_PUBLISHED);
        }
        if (exam.getStatus() == ExamStatus.ARCHIVED) {
            return buildEditorResponse(exam); // idempotent
        }
        List<ExamSession> active = sessionRepository.findAllByExamIdAndStatusIn(examId,
                List.of(ExamSessionStatus.SCHEDULED, ExamSessionStatus.OPEN));
        if (!active.isEmpty()) {
            throw new ExamException(ExamErrorCode.EXAM_ARCHIVE_CONFLICT);
        }
        exam.markArchived();
        examRepository.saveAndFlush(exam);
        return buildEditorResponse(exam);
    }

    // ============================================================
    // Shared response assembly
    // ============================================================

    /** Builds the management response for an already-loaded, accessible exam. */
    private TeacherExamEditorResponse buildEditorResponse(Exam exam) {
        Subject subject = exam.getSubjectId() != null ? subjectRepository.findById(exam.getSubjectId()).orElse(null)
                : null;
        ExamPurpose purpose = exam.getPurposeId() != null
                ? purposeRepository.findById(exam.getPurposeId()).orElse(null)
                : null;
        List<ExamSectionResponse> composition = loadComposition(exam.getId());
        return new TeacherExamEditorResponse(
                exam.getId(), exam.getCode(), exam.getTitle(), exam.getDescription(),
                subject != null ? new SubjectSummary(subject.getId(), subject.getCode(), subject.getName()) : null,
                purpose != null ? new ExamPurposeSummary(purpose.getId(), purpose.getCode(), purpose.getTitle()) : null,
                exam.getStatus().name(),
                exam.getDurationMinutes(), exam.getInstructions(),
                deepCopy(exam.getTfMatrixScoring()),
                composition,
                exam.getTotalPoints(),
                exam.getPublishedAt(),
                exam.getSourceExamId(),
                exam.getVersion(),
                exam.getCreatedAt(), exam.getUpdatedAt());
    }

    // ============================================================
    // Composition helpers
    // ============================================================

    private void validateCompositionStructure(UpdateDraftCompositionRequest request) {
        Set<Integer> sectionPositions = new HashSet<>();
        Set<Long> sourceIds = new HashSet<>();
        for (var section : request.sections()) {
            if (!sectionPositions.add(section.position())) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
            Set<Integer> questionPositions = new HashSet<>();
            for (var q : section.questions()) {
                if (!questionPositions.add(q.position())) {
                    throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
                }
                if (!sourceIds.add(q.sourceQuestionId())) {
                    throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
                }
            }
        }
    }

    /**
     * Batch-loads + validates every source question, pins each current
     * QuestionVersion, and stages the new composition graph in memory.
     */
    @SuppressWarnings("null")
    private List<StagedSection> resolveAndStageSources(UpdateDraftCompositionRequest request, Exam exam) {
        List<Long> sourceQuestionIds = request.sections().stream()
                .flatMap(s -> s.questions().stream())
                .map(rq -> rq.sourceQuestionId())
                .distinct()
                .toList();

        if (sourceQuestionIds.isEmpty()) {
            List<StagedSection> staged = new ArrayList<>();
            for (var sec : request.sections()) {
                ExamSection section = new ExamSection(exam.getId(), sec.title(), sec.position());
                section.setInstructions(sec.instructions());
                staged.add(new StagedSection(section, List.of()));
            }
            return staged;
        }

        Map<Long, Question> questions = sourceQuestionRepository.findAllById(sourceQuestionIds).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        for (Long id : sourceQuestionIds) {
            if (!questions.containsKey(id)) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
        }

        Set<Long> bankIds = questions.values().stream().map(Question::getQuestionBankId).collect(Collectors.toSet());
        Map<Long, QuestionBank> banks = sourceBankRepository.findAllById(bankIds).stream()
                .collect(Collectors.toMap(QuestionBank::getId, b -> b));
        for (Question q : questions.values()) {
            if (q.getStatus() != QuestionStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
            QuestionBank bank = banks.get(q.getQuestionBankId());
            if (bank == null || bank.getStatus() != QuestionBankStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
            // STRICT source ownership: same workspace + same subject scope.
            if (!bank.getWorkspaceId().equals(exam.getWorkspaceId())
                    || !Objects.equals(bank.getSubjectId(), exam.getSubjectId())) {
                throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED);
            }
        }

        // Pin each question's current version.
        Map<Long, QuestionVersion> currentVersions = new HashMap<>();
        for (QuestionVersion v : sourceVersionRepository.findAllByQuestionIdIn(sourceQuestionIds)) {
            Question q = questions.get(v.getQuestionId());
            if (q != null && v.getVersionNumber().equals(q.getCurrentVersionNumber())) {
                currentVersions.put(v.getQuestionId(), v);
            }
        }
        for (Long id : sourceQuestionIds) {
            if (!currentVersions.containsKey(id)) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
        }

        List<Long> pinnedVersionIds = currentVersions.values().stream().map(QuestionVersion::getId).toList();
        Map<Long, List<QuestionOption>> optionsByVersion = sourceOptionRepository
                .findAllByQuestionVersionIdInOrderByQuestionVersionIdAscPositionAsc(pinnedVersionIds).stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionVersionId));

        List<StagedSection> staged = new ArrayList<>();
        for (var sec : request.sections()) {
            ExamSection section = new ExamSection(exam.getId(), sec.title(), sec.position());
            section.setInstructions(sec.instructions());
            List<ResolvedQuestion> stagedQuestions = new ArrayList<>();
            for (var rq : sec.questions()) {
                Question q = questions.get(rq.sourceQuestionId());
                QuestionVersion v = currentVersions.get(rq.sourceQuestionId());
                BigDecimal points = rq.defaultPoints() != null ? rq.defaultPoints() : v.getDefaultPoints();
                List<ResolvedOption> opts = optionsByVersion.getOrDefault(v.getId(), List.of()).stream()
                        .map(o -> new ResolvedOption(o.getOptionKey(), o.getContent(),
                                o.getIsCorrect() != null && o.getIsCorrect(), o.getPosition()))
                        .toList();
                stagedQuestions.add(new ResolvedQuestion(q.getId(), v.getId(), q.getCode(),
                        v.getQuestionType(), v.getContent(), points, rq.position(),
                        v.getDifficulty(), v.getExplanation(),
                        deepCopy(v.getAnswerKey()), deepCopy(v.getMetadata()), opts));
            }
            staged.add(new StagedSection(section, stagedQuestions));
        }
        return staged;
    }

    /** Atomic composition replace: delete old graph (FK-safe) then insert new. */
    @SuppressWarnings("null")
    private void replaceComposition(Long examId, List<StagedSection> staged) {
        List<Long> oldQuestionIds = questionRepository.findAllByExamId(examId).stream()
                .map(ExamQuestion::getId).filter(Objects::nonNull).toList();
        if (!oldQuestionIds.isEmpty()) {
            optionRepository.deleteAllByExamQuestionIdIn(oldQuestionIds);
            optionRepository.flush();
        }
        questionRepository.deleteAllByExamId(examId);
        questionRepository.flush();
        sectionRepository.deleteAllByExamId(examId);
        sectionRepository.flush();

        for (StagedSection ss : staged) {
            ExamSection savedSection = sectionRepository.saveAndFlush(ss.section());
            for (ResolvedQuestion rq : ss.questions()) {
                ExamQuestion eq = new ExamQuestion(ss.section().getExamId(), savedSection.getId(),
                        rq.sourceQuestionId(), rq.pinnedVersionId(), rq.code(), rq.type(),
                        rq.content(), rq.points(), rq.position());
                eq.setDifficulty(rq.difficulty());
                eq.setExplanation(rq.explanation());
                eq.setAnswerKey(rq.answerKey());
                eq.setMetadata(rq.metadata());
                ExamQuestion savedQuestion = questionRepository.saveAndFlush(eq);
                if (!rq.options().isEmpty()) {
                    List<ExamQuestionOption> opts = rq.options().stream()
                            .map(o -> new ExamQuestionOption(savedQuestion.getId(), o.optionKey(),
                                    o.content(), o.correct(), o.position()))
                            .toList();
                    optionRepository.saveAll(opts);
                }
            }
        }
        optionRepository.flush();
    }

    /** Deep-copies sections/questions/options between two exams (duplicate). */
    @SuppressWarnings("null")
    private void deepCopyComposition(Long sourceExamId, Long targetExamId) {
        List<ExamSection> sourceSections = sectionRepository.findAllByExamIdOrderByPositionAsc(sourceExamId);
        List<Long> sourceSectionIds = sourceSections.stream().map(ExamSection::getId).toList();
        List<ExamQuestion> sourceQuestions = sourceSectionIds.isEmpty()
                ? List.of()
                : questionRepository.findAllByExamSectionIdInOrderByExamSectionIdAscPositionAsc(sourceSectionIds);
        List<Long> sourceQuestionIds = sourceQuestions.stream().map(ExamQuestion::getId).toList();
        List<ExamQuestionOption> sourceOptions = sourceQuestionIds.isEmpty()
                ? List.of()
                : optionRepository.findAllByExamQuestionIdInOrderByExamQuestionIdAscPositionAsc(sourceQuestionIds);

        Map<Long, List<ExamQuestion>> questionsBySection = sourceQuestions.stream()
                .collect(Collectors.groupingBy(ExamQuestion::getExamSectionId));
        Map<Long, List<ExamQuestionOption>> optionsByQuestion = sourceOptions.stream()
                .collect(Collectors.groupingBy(ExamQuestionOption::getExamQuestionId));

        for (ExamSection ss : sourceSections) {
            ExamSection newSection = new ExamSection(targetExamId, ss.getTitle(), ss.getPosition());
            newSection.setCode(ss.getCode());
            newSection.setInstructions(ss.getInstructions());
            sectionRepository.saveAndFlush(newSection);
            for (ExamQuestion sq : questionsBySection.getOrDefault(ss.getId(), List.of())) {
                ExamQuestion nq = new ExamQuestion(targetExamId, newSection.getId(),
                        sq.getSourceQuestionId(), sq.getSourceQuestionVersionId(),
                        sq.getQuestionCode(), sq.getQuestionType(), sq.getContent(),
                        sq.getDefaultPoints(), sq.getPosition());
                nq.setDifficulty(sq.getDifficulty());
                nq.setExplanation(sq.getExplanation());
                nq.setAnswerKey(deepCopy(sq.getAnswerKey()));
                nq.setMetadata(deepCopy(sq.getMetadata()));
                ExamQuestion savedQuestion = questionRepository.saveAndFlush(nq);
                List<ExamQuestionOption> opts = optionsByQuestion.getOrDefault(sq.getId(), List.of());
                if (!opts.isEmpty()) {
                    optionRepository.saveAll(opts.stream()
                            .map(so -> new ExamQuestionOption(savedQuestion.getId(), so.getOptionKey(),
                                    so.getContent(), so.getIsCorrect(), so.getPosition()))
                            .toList());
                    optionRepository.flush();
                }
            }
        }
    }

    // Resolved-source + staged-graph holders.
    private record StagedSection(ExamSection section, List<ResolvedQuestion> questions) {
    }

    private record ResolvedQuestion(Long sourceQuestionId, Long pinnedVersionId, String code,
            QuestionType type, String content, BigDecimal points, Integer position,
            QuestionDifficulty difficulty, String explanation,
            JsonNode answerKey, JsonNode metadata, List<ResolvedOption> options) {
    }

    private record ResolvedOption(String optionKey, String content, boolean correct, Integer position) {
    }

    // ============================================================
    // Detail loading (no N+1)
    // ============================================================

    @SuppressWarnings("null")
    private List<ExamSectionResponse> loadComposition(Long examId) {
        List<ExamSection> sections = sectionRepository.findAllByExamIdOrderByPositionAsc(examId);
        List<Long> sectionIds = sections.stream().map(ExamSection::getId).toList();

        List<ExamQuestion> questions = sectionIds.isEmpty()
                ? List.of()
                : questionRepository.findAllByExamSectionIdInOrderByExamSectionIdAscPositionAsc(sectionIds);
        List<Long> questionIds = questions.stream().map(ExamQuestion::getId).toList();

        List<ExamQuestionOption> options = questionIds.isEmpty()
                ? List.of()
                : optionRepository.findAllByExamQuestionIdInOrderByExamQuestionIdAscPositionAsc(questionIds);

        Map<Long, List<ExamQuestionOption>> optionsByQuestion = options.stream()
                .collect(Collectors.groupingBy(ExamQuestionOption::getExamQuestionId));
        Map<Long, List<ExamQuestion>> questionsBySection = questions.stream()
                .collect(Collectors.groupingBy(ExamQuestion::getExamSectionId));

        return sections.stream()
                .map(s -> new ExamSectionResponse(
                        s.getId(), s.getPosition(), s.getTitle(), s.getInstructions(),
                        questionsBySection.getOrDefault(s.getId(), List.of()).stream()
                                .map(q -> mapQuestion(q, optionsByQuestion.getOrDefault(q.getId(), List.of())))
                                .toList()))
                .toList();
    }

    private ExamQuestionResponse mapQuestion(ExamQuestion q, List<ExamQuestionOption> opts) {
        return new ExamQuestionResponse(
                q.getId(), q.getPosition(),
                q.getSourceQuestionId(), q.getSourceQuestionVersionId(),
                q.getQuestionCode(), q.getQuestionType().name(),
                q.getContent(), q.getDefaultPoints(),
                q.getDifficulty() != null ? q.getDifficulty().name() : null,
                q.getExplanation(),
                deepCopy(q.getAnswerKey()), deepCopy(q.getMetadata()),
                q.getExamSectionId(),
                opts.stream()
                        .map(o -> new ExamQuestionOptionResponse(
                                o.getId(), o.getOptionKey(), o.getContent(),
                                o.getIsCorrect(), o.getPosition()))
                        .toList());
    }

    // ============================================================
    // Authorization helpers
    // ============================================================

    /**
     * Reads allow the creator or any ACTIVE member of the exam's workspace
     * (404 anti-enumeration for outsiders). P0-2 (issue #4): in ORGANIZATION
     * workspaces the creator is additionally gated on an ACTIVE membership —
     * a revoked creator is denied.
     */
    private Exam requireViewableExam(Long userId, Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        if (exam.getCreatedByUserUserId().equals(userId)) {
            requireWorkspaceAccess(userId, exam);
            return exam;
        }
        if (workspaceAccessService.isActiveMember(userId, exam.getWorkspaceId())) {
            return exam;
        }
        throw new ExamException(ExamErrorCode.EXAM_NOT_FOUND);
    }

    /**
     * Mutations require the creator or a workspace OWNER/ADMIN (workspace
     * privilege escalation via manipulated ids is rejected). P0-2 (issue #4):
     * the creator of an ORGANIZATION-workspace exam must still hold an ACTIVE
     * membership; only the authenticated owner of a PERSONAL workspace
     * bypasses membership.
     */
    private void requireManageableExam(Long userId, Exam exam) {
        if (exam.getCreatedByUserUserId().equals(userId)) {
            try {
                workspaceAccessService.requireResourceRead(userId, exam.getWorkspaceId());
            } catch (com.quizopia.backend.workspace.exception.WorkspaceException ex) {
                throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED);
            }
            return;
        }
        try {
            workspaceAccessService.requireResourceManage(userId, exam.getWorkspaceId(),
                    exam.getCreatedByUserUserId());
        } catch (com.quizopia.backend.workspace.exception.WorkspaceException ex) {
            throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED);
        }
    }

    /**
     * Creator access is only honored while the workspace grants it: PERSONAL
     * workspaces — the authenticated owner always qualifies; ORGANIZATION
     * workspaces — an ACTIVE membership is required (issue #4 P0-2).
     * Used by the read path (404 anti-enumeration for revoked creators).
     */
    private void requireWorkspaceAccess(Long userId, Exam exam) {
        try {
            workspaceAccessService.requireResourceRead(userId, exam.getWorkspaceId());
        } catch (com.quizopia.backend.workspace.exception.WorkspaceException ex) {
            throw new ExamException(ExamErrorCode.EXAM_NOT_FOUND);
        }
    }

    /**
     * Resolves the authoring workspace (issue #1 A3): explicit validated
     * workspaceId, else teacher-profile org workspace, else personal.
     */
    private Workspace resolveAuthoringWorkspace(Long userId, Long requestedWorkspaceId) {
        if (requestedWorkspaceId != null) {
            workspaceAccessService.requireContentEditor(userId, requestedWorkspaceId);
            return workspaceRepository.findById(requestedWorkspaceId)
                    .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        }
        TeacherProfile profile = teacherProfileRepository.findByUserId(userId).orElse(null);
        if (profile != null) {
            Long workspaceId = organizationWorkspaceResolver.requireOrganizationWorkspaceId(profile.getSchoolId());
            return workspaceRepository.findById(workspaceId)
                    .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        }
        return workspaceProvisioningService.ensurePersonalWorkspace(
                userRepository.findById(userId)
                        .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED)));
    }

    private Long requireOrganizationSchoolId(Workspace workspace) {
        return organizationRepository.findById(workspace.getOrganizationId())
                .map(org -> org.getLegacySchoolId())
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_SCHOOL_WORKSPACE_NOT_FOUND));
    }

    // ============================================================
    // Publish validation
    // ============================================================

    @SuppressWarnings("deprecation")
    private void validatePublishedQuestionType(QuestionType type, List<QuestionOption> options, JsonNode answerKey) {
        int count = options.size();
        long correct = options.stream().filter(o -> o.getIsCorrect() != null && o.getIsCorrect()).count();
        switch (type) {
            case SINGLE_CHOICE -> {
                if (count < 4 || count > 6 || correct != 1) {
                    throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
                }
            }
            case MULTIPLE_CHOICE -> {
                if (count < 4 || count > 6 || correct < 2) {
                    throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
                }
            }
            case TRUE_FALSE_MATRIX -> {
                if (count != 4) {
                    throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
                }
            }
            case NUMERIC_FILL -> {
                if (count != 0
                        || answerKey == null
                        || !answerKey.path("expectedAnswer").isTextual()
                        || answerKey.path("expectedAnswer").asString().length() != 4
                        || !answerKey.path("expectedAnswer").asString().matches("^-?[0-9]+([.][0-9]+)?$")) {
                    throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
                }
            }
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** deep-copy JsonNode from entity to prevent mutable aliasing in DTOs. */
    private static JsonNode deepCopy(JsonNode node) {
        return node != null ? node.deepCopy() : null;
    }

    private ExamStatus parseStatusFilter(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            return null;
        }
        try {
            return ExamStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
        }
    }

    private PageRequest safePageable(int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Sort safeSort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String prop = parts[0].trim();
            Sort.Direction dir = (parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc"))
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;
            if (SORT_ALLOWLIST.contains(prop)) {
                safeSort = Sort.by(dir, prop).and(Sort.by(Sort.Direction.DESC, "id"));
            } else {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR);
            }
        } else {
            safeSort = safeSort.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        return PageRequest.of(safePage, safeSize, safeSort);
    }

    @SuppressWarnings("null")
    private Map<Long, SubjectSummary> batchSubjects(List<Exam> exams) {
        Set<Long> ids = exams.stream().map(Exam::getSubjectId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return subjectRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Subject::getId,
                        s -> new SubjectSummary(s.getId(), s.getCode(), s.getName())));
    }

    @SuppressWarnings("null")
    private Map<Long, ExamPurposeSummary> batchPurposes(List<Exam> exams) {
        Set<Long> ids = exams.stream().map(Exam::getPurposeId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return purposeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ExamPurpose::getId,
                        p -> new ExamPurposeSummary(p.getId(), p.getCode(), p.getTitle())));
    }

    @SuppressWarnings("null")
    private Map<Long, Long> batchQuestionCounts(List<Exam> exams) {
        List<Long> ids = exams.stream().map(Exam::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : questionRepository.countByExamIdsIn(ids)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private static boolean isCodeConflict(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && (name.contains(CODE_CONFLICT_CONSTRAINT)
                        || name.contains("uk_exams_owner_code_ci"))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}
