package com.hhtuann.backend.exam.application;

import com.hhtuann.backend.academic.domain.model.AcademicStatus;
import com.hhtuann.backend.academic.domain.model.Subject;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.SubjectRepository;
import com.hhtuann.backend.common.BusinessCodes;
import com.hhtuann.backend.exam.domain.model.*;
import com.hhtuann.backend.exam.dto.*;
import com.hhtuann.backend.exam.exception.ExamErrorCode;
import com.hhtuann.backend.exam.exception.ExamException;
import com.hhtuann.backend.exam.repository.*;
import com.hhtuann.backend.question.domain.model.Question;
import com.hhtuann.backend.question.domain.model.QuestionBank;
import com.hhtuann.backend.question.domain.model.QuestionBankStatus;
import com.hhtuann.backend.question.domain.model.QuestionDifficulty;
import com.hhtuann.backend.question.domain.model.QuestionOption;
import com.hhtuann.backend.question.domain.model.QuestionStatus;
import com.hhtuann.backend.question.domain.model.QuestionType;
import com.hhtuann.backend.question.domain.model.QuestionVersion;
import com.hhtuann.backend.question.dto.PageResponse;
import com.hhtuann.backend.question.dto.SubjectSummary;
import com.hhtuann.backend.question.repository.QuestionBankRepository;
import com.hhtuann.backend.question.repository.QuestionOptionRepository;
import com.hhtuann.backend.question.repository.QuestionRepository;
import com.hhtuann.backend.question.repository.QuestionVersionRepository;
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
 * Application service for the Exam API — 5 endpoints (A3.2-1 + A3.2-2A):
 * <ul>
 * <li>GET /api/exam-purposes — list purposes for caller's school</li>
 * <li>POST /api/exams — create exam + DRAFT v1 in one transaction</li>
 * <li>GET /api/exams/my — paginated owner-scoped list with search/filter</li>
 * <li>GET /api/exams/{examId} — editor detail (DRAFT + published
 * summaries)</li>
 * <li>PUT /api/exams/{examId}/draft/composition — replace DRAFT snapshot, PIN
 * source versions</li>
 * </ul>
 *
 * <p>
 * Authorization (deny by default): active TEACHER role (DB) + effective
 * permission (DB) + TeacherProfile. No JWT-claim authority, no role hierarchy.
 *
 * <p>
 * JsonNode fields mapped to DTOs use deepCopy to prevent mutable aliasing.
 */
@Service
public class ExamService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORT_ALLOWLIST = Set.of("createdAt", "title", "code", "status",
            "currentVersionNumber");
    private static final String CODE_CONFLICT_CONSTRAINT = "uk_exams_owner_code_ci";

    private final ExamAuthorizationService auth;
    private final SubjectRepository subjectRepository;
    private final ExamPurposeRepository purposeRepository;
    private final ExamRepository examRepository;
    private final ExamVersionRepository versionRepository;
    private final ExamSectionRepository sectionRepository;
    private final ExamQuestionRepository questionRepository;
    private final ExamQuestionOptionRepository optionRepository;
    private final QuestionRepository sourceQuestionRepository;
    private final QuestionBankRepository sourceBankRepository;
    private final QuestionVersionRepository sourceVersionRepository;
    private final QuestionOptionRepository sourceOptionRepository;

    public ExamService(ExamAuthorizationService auth,
            SubjectRepository subjectRepository,
            ExamPurposeRepository purposeRepository,
            ExamRepository examRepository,
            ExamVersionRepository versionRepository,
            ExamSectionRepository sectionRepository,
            ExamQuestionRepository questionRepository,
            ExamQuestionOptionRepository optionRepository,
            QuestionRepository sourceQuestionRepository,
            QuestionBankRepository sourceBankRepository,
            QuestionVersionRepository sourceVersionRepository,
            QuestionOptionRepository sourceOptionRepository) {
        this.auth = auth;
        this.subjectRepository = subjectRepository;
        this.purposeRepository = purposeRepository;
        this.examRepository = examRepository;
        this.versionRepository = versionRepository;
        this.sectionRepository = sectionRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.sourceQuestionRepository = sourceQuestionRepository;
        this.sourceBankRepository = sourceBankRepository;
        this.sourceVersionRepository = sourceVersionRepository;
        this.sourceOptionRepository = sourceOptionRepository;
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
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_CREATE");
        Long schoolId = profile.getSchoolId();

        Subject subject = subjectRepository.findById(request.subjectId())
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_SUBJECT_NOT_FOUND));
        if (!subject.getSchoolId().equals(schoolId)) {
            throw new ExamException(ExamErrorCode.EXAM_SUBJECT_SCHOOL_MISMATCH);
        }
        if (subject.getStatus() != AcademicStatus.ACTIVE) {
            throw new ExamException(ExamErrorCode.EXAM_SUBJECT_NOT_FOUND);
        }

        // Purpose (nullable) — if present must exist + same school
        ExamPurpose purpose = null;
        if (request.purposeId() != null) {
            purpose = purposeRepository.findById(request.purposeId())
                    .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_PURPOSE_NOT_FOUND));
            if (!purpose.getSchoolId().equals(schoolId)) {
                throw new ExamException(ExamErrorCode.EXAM_PURPOSE_SCHOOL_MISMATCH);
            }
        }

        String code = (request.code() == null || request.code().isBlank())
                ? BusinessCodes.readableCode("EX", 8,
                        c -> examRepository.existsByOwnerTeacherIdAndCodeIgnoreCase(profile.getId(), c))
                : request.code().trim();
        Exam exam = new Exam(schoolId, subject.getId(), profile.getId(), code, request.title());
        exam.setDescription(request.description());
        if (purpose != null) {
            exam.setPurposeId(purpose.getId());
        }

        try {
            exam = examRepository.saveAndFlush(exam);
            // Create DRAFT v1 in same transaction; rollback exam if version fails
            ExamVersion draft = new ExamVersion(schoolId, exam.getId(), 1, userId);
            versionRepository.saveAndFlush(draft);
        } catch (DataIntegrityViolationException ex) {
            if (isCodeConflict(ex)) {
                throw new ExamException(ExamErrorCode.EXAM_CODE_CONFLICT);
            }
            throw ex;
        }

        return new ExamListItem(
                exam.getId(), exam.getCode(), exam.getTitle(),
                new SubjectSummary(subject.getId(), subject.getCode(), subject.getName()),
                purpose != null ? new ExamPurposeSummary(purpose.getId(), purpose.getCode(), purpose.getTitle()) : null,
                exam.getStatus().name(), exam.getCurrentVersionNumber(),
                true, false,
                exam.getCreatedAt());
    }

    // ============================================================
    // GET /api/exams/my
    // ============================================================

    @SuppressWarnings("null")
    @Transactional(readOnly = true)
    public PageResponse<ExamListItem> listMyExams(
            Long userId, String search, Long subjectId, String statusStr,
            int page, int size, String sort) {

        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_READ");

        ExamStatus statusFilter = parseStatusFilter(statusStr);
        PageRequest pageable = safePageable(page, size, sort);

        String trimmedSearch = (search == null || search.isBlank()) ? null : search.trim();

        Page<Exam> exams = examRepository.findOwnedByTeacher(
                profile.getId(), trimmedSearch, subjectId, statusFilter, pageable);

        // Batch load subjects + purposes (no N+1)
        Map<Long, SubjectSummary> subjectMap = batchSubjects(exams.getContent());
        Map<Long, ExamPurposeSummary> purposeMap = batchPurposes(exams.getContent());

        // Batch load hasDraft/hasPublished flags — single query for ALL exam IDs (no
        // N+1)
        List<Long> examIds = exams.getContent().stream().map(Exam::getId).toList();
        Set<Long> hasDraft = new HashSet<>();
        Set<Long> hasPublished = new HashSet<>();
        if (!examIds.isEmpty()) {
            for (var vs : versionRepository.findStatusByExamIdsIn(examIds)) {
                if (vs.getStatus() == ExamVersionStatus.DRAFT) {
                    hasDraft.add(vs.getExamId());
                } else if (vs.getStatus() == ExamVersionStatus.PUBLISHED) {
                    hasPublished.add(vs.getExamId());
                }
            }
        }

        List<ExamListItem> items = exams.getContent().stream()
                .map(e -> new ExamListItem(
                        e.getId(), e.getCode(), e.getTitle(),
                        subjectMap.get(e.getSubjectId()),
                        e.getPurposeId() != null ? purposeMap.get(e.getPurposeId()) : null,
                        e.getStatus().name(), e.getCurrentVersionNumber(),
                        hasDraft.contains(e.getId()),
                        hasPublished.contains(e.getId()),
                        e.getCreatedAt()))
                .toList();

        return new PageResponse<>(items, exams.getNumber(), exams.getSize(),
                exams.getTotalElements(), exams.getTotalPages(),
                pageable.getSort().toString());
    }

    // ============================================================
    // GET /api/exams/{examId}
    // ============================================================

    @Transactional(readOnly = true)
    public TeacherExamEditorResponse getExamDetail(Long userId, Long examId) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_READ");

        // 404 for both missing and foreign-owner (anti-enumeration)
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        if (!exam.getOwnerTeacherId().equals(profile.getId())) {
            throw new ExamException(ExamErrorCode.EXAM_NOT_FOUND);
        }

        return buildEditorResponse(exam);
    }

    // ============================================================
    // PUT /api/exams/{examId}/draft/composition — snapshot + PIN
    // ============================================================

    @Transactional
    public TeacherExamEditorResponse updateDraftComposition(Long userId, Long examId,
            UpdateDraftCompositionRequest request) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_UPDATE");

        // Pessimistic-lock the exam row before reading/mutating the DRAFT.
        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        // Mutation endpoints deny foreign owners with 403 (reads use 404
        // anti-enumeration).
        if (!exam.getOwnerTeacherId().equals(profile.getId())) {
            throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED);
        }

        // Single DRAFT (partial unique guarantees <= 1).
        ExamVersion draft = versionRepository.findFirstByExamIdAndStatus(examId, ExamVersionStatus.DRAFT)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_VERSION_NOT_DRAFT));

        // Optimistic token: expectedVersionNumber must equal the DRAFT content version
        // number.
        if (!draft.getVersionNumber().equals(request.expectedVersionNumber())) {
            throw new ExamException(ExamErrorCode.EXAM_CONCURRENT_MODIFICATION);
        }

        // Structural validation BEFORE any destructive work (old graph survives on
        // error).
        validateCompositionStructure(request);

        // Resolve + validate sources, pinning each current QuestionVersion (batch, no
        // per-source query).
        Long draftVersionId = draft.getId();
        List<StagedSection> staged = resolveAndStageSources(request, exam, draftVersionId);

        // ATOMIC REPLACE: delete old DRAFT graph in FK-safe order, then insert the new
        // graph.
        replaceDraftComposition(draftVersionId, staged);

        // Apply optional DRAFT settings (nullable => keep existing value).
        Integer duration = request.durationMinutes() != null ? request.durationMinutes() : draft.getDurationMinutes();
        String instructions = request.instructions() != null ? request.instructions() : draft.getInstructions();
        draft.updateDraftSettings(duration, instructions, draft.getTitle());
        versionRepository.saveAndFlush(draft);

        return buildEditorResponse(exam);
    }

    // ============================================================
    // POST /api/exams/{examId}/versions — create next DRAFT (clone a PUBLISHED
    // version)
    // ============================================================

    @Transactional
    public CreateExamVersionResponse createNextVersion(Long userId, Long examId,
            CreateExamVersionRequest request) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_VERSION_CREATE");

        // Pessimistic-lock the exam row before reading/deciding version numbers.
        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        // Owner + school scope (mutation contract).
        if (!exam.getOwnerTeacherId().equals(profile.getId())
                || !exam.getSchoolId().equals(profile.getSchoolId())) {
            throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED);
        }

        // At most one DRAFT per exam; refuse to create a second.
        if (versionRepository.existsByExamIdAndStatus(examId, ExamVersionStatus.DRAFT)) {
            throw new ExamException(ExamErrorCode.EXAM_VERSION_NOT_DRAFT);
        }

        // Select the PUBLISHED source to clone.
        ExamVersion source;
        if (request.cloneFromVersionNumber() != null) {
            source = versionRepository.findByExamIdAndVersionNumber(examId, request.cloneFromVersionNumber())
                    .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_VERSION_NOT_FOUND));
            if (source.getStatus() != ExamVersionStatus.PUBLISHED) {
                throw new ExamException(ExamErrorCode.EXAM_VERSION_NOT_DRAFT); // explicit source is not PUBLISHED
            }
        } else {
            // Latest PUBLISHED by versionNumber DESC (ordered query — do NOT use unordered
            // findFirstByExamIdAndStatus).
            source = versionRepository
                    .findFirstByExamIdAndStatusOrderByVersionNumberDesc(examId, ExamVersionStatus.PUBLISHED)
                    .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_VERSION_NOT_DRAFT)); // no PUBLISHED to
                                                                                                 // clone
        }

        // New DRAFT version number = currentVersionNumber + 1 (NOT Exam.@Version;
        // currentVersionNumber
        // is NOT bumped before publish — frozen contract).
        int newVersionNumber = exam.getCurrentVersionNumber() + 1;
        ExamVersion draft = cloneVersion(exam, source, newVersionNumber, userId);
        // Exam stays as-is: READY remains READY; currentVersionNumber unchanged until
        // publish.
        return new CreateExamVersionResponse(draft.getVersionNumber(), draft.getStatus().name(),
                source.getVersionNumber());
    }

    /**
     * Deep-copies a PUBLISHED version's composition into a fresh DRAFT. Pinned
     * source IDs
     * (sourceQuestionId/sourceQuestionVersionId) are preserved verbatim — NO
     * Question Bank
     * current-version resolution, NO refresh. All children get new ids; JsonNode
     * fields are
     * deep-copied (no shared references between versions). DRAFT totalPoints stays
     * 0
     * (the published totalPoints is NOT copied; DRAFT invariant allows 0).
     */
    @SuppressWarnings("null")
    private ExamVersion cloneVersion(Exam exam, ExamVersion source, int newVersionNumber, Long createdBy) {
        ExamVersion draft = new ExamVersion(exam.getSchoolId(), exam.getId(), newVersionNumber, createdBy);
        draft.updateDraftSettings(source.getDurationMinutes(), source.getInstructions(), source.getTitle());
        draft = versionRepository.saveAndFlush(draft);
        Long newVersionId = draft.getId();

        // Batch-load the source graph (sections -> questions -> options); no per-row
        // query.
        List<ExamSection> sourceSections = sectionRepository.findAllByExamVersionIdOrderByPositionAsc(source.getId());
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
            ExamSection newSection = new ExamSection(newVersionId, ss.getTitle(), ss.getPosition());
            newSection.setCode(ss.getCode());
            newSection.setInstructions(ss.getInstructions());
            sectionRepository.saveAndFlush(newSection);
            for (ExamQuestion sq : questionsBySection.getOrDefault(ss.getId(), List.of())) {
                ExamQuestion nq = new ExamQuestion(newVersionId, newSection.getId(),
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
        return draft;
    }

    // ============================================================
    // POST /api/exams/{examId}/publish — refresh from PINNED + PUBLISHED (1 tx)
    // ============================================================

    @SuppressWarnings("null")
    @Transactional
    public PublishedExamSummary publishExam(Long userId, Long examId, PublishExamRequest request) {
        TeacherProfile profile = auth.requireTeacherWithPermission(userId, "EXAM_PUBLISH");

        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new ExamException(ExamErrorCode.EXAM_NOT_FOUND));
        if (!exam.getOwnerTeacherId().equals(profile.getId())
                || !exam.getSchoolId().equals(profile.getSchoolId())) {
            throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED);
        }

        // Resolve the single DRAFT. If none: a PUBLISHED version existing alongside no
        // DRAFT
        // means the DRAFT was already published (concurrent winner OR repeat) ->
        // PUBLISH_CONFLICT.
        ExamVersion draft = versionRepository.findFirstByExamIdAndStatus(examId, ExamVersionStatus.DRAFT)
                .orElse(null);
        if (draft == null) {
            if (versionRepository.existsByExamIdAndStatus(examId, ExamVersionStatus.PUBLISHED)) {
                throw new ExamException(ExamErrorCode.EXAM_PUBLISH_CONFLICT);
            }
            throw new ExamException(ExamErrorCode.EXAM_VERSION_NOT_DRAFT);
        }

        // Optimistic token (optional): mismatch -> PUBLISH_CONFLICT.
        if (request != null && request.expectedVersionNumber() != null
                && !request.expectedVersionNumber().equals(draft.getVersionNumber())) {
            throw new ExamException(ExamErrorCode.EXAM_PUBLISH_CONFLICT);
        }

        Long draftVersionId = draft.getId();
        // Batch-load DRAFT graph (sections -> questions).
        List<ExamSection> sections = sectionRepository.findAllByExamVersionIdOrderByPositionAsc(draftVersionId);
        if (sections.isEmpty()) {
            throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // empty composition
        }
        List<Long> sectionIds = sections.stream().map(ExamSection::getId).toList();
        List<ExamQuestion> questions = questionRepository
                .findAllByExamSectionIdInOrderByExamSectionIdAscPositionAsc(sectionIds);
        Map<Long, List<ExamQuestion>> questionsBySection = questions.stream()
                .collect(Collectors.groupingBy(ExamQuestion::getExamSectionId));
        for (ExamSection s : sections) {
            if (questionsBySection.getOrDefault(s.getId(), List.of()).isEmpty()) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // empty section
            }
        }
        List<Long> questionIds = questions.stream().map(ExamQuestion::getId).toList();

        // Batch-load pinned QuestionVersions (by sourceQuestionVersionId), source
        // Questions+Banks,
        // and pinned options (by version ids) — revalidate + refresh from the PINNED
        // immutable version.
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
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // pinned version / source gone
            }
            if (!pv.getQuestionId().equals(sq.getId())) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // provenance mismatch
            }
            if (sq.getStatus() != QuestionStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // source question archived
            }
            QuestionBank bank = sourceBanks.get(sq.getQuestionBankId());
            if (bank == null || bank.getStatus() != QuestionBankStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // bank missing/archived
            }
            if (!bank.getOwnerTeacherId().equals(exam.getOwnerTeacherId())
                    || !bank.getSchoolId().equals(exam.getSchoolId())
                    || !bank.getSubjectId().equals(exam.getSubjectId())) {
                throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED); // ownership drift since PUT
            }
            // Refresh settable snapshot fields from the PINNED immutable version
            // (idempotent).
            // content/type/questionCode/defaultPoints have no setter but are already
            // correct:
            // PUT snapshotted them from this same immutable pinned version, and the
            // composite FK
            // (source_question_id, source_question_version_id) + version immutability
            // guarantee it.
            eq.setDifficulty(pv.getDifficulty());
            eq.setExplanation(pv.getExplanation());
            eq.setAnswerKey(deepCopy(pv.getAnswerKey()));
            eq.setMetadata(deepCopy(pv.getMetadata()));
            // Validate question-type shape against the pinned options (the refresh source
            // of truth).
            validatePublishedQuestionType(pv.getQuestionType(),
                    pinnedOptionsByVersion.getOrDefault(pv.getId(), List.of()), pv.getAnswerKey());
            totalPoints = totalPoints.add(eq.getDefaultPoints());
        }
        if (totalPoints.signum() <= 0) {
            throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // totalPoints must be > 0
        }
        questionRepository.flush(); // persist refreshed snapshot fields before the PUBLISHED transition

        // Replace options from the pinned options (idempotent: pinned immutable == PUT
        // snapshot).
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

        // Transition: DRAFT -> PUBLISHED (single instant for publishedAt).
        Instant publishedAt = Instant.now();
        draft.markPublished(publishedAt, totalPoints);
        versionRepository.saveAndFlush(draft);
        // Exam: READY + currentVersionNumber = published version number (NOT bumped
        // before publish).
        exam.markReady();
        exam.advanceCurrentVersion(draft.getVersionNumber());
        examRepository.saveAndFlush(exam);

        return new PublishedExamSummary(exam.getId(), draft.getVersionNumber(), draft.getStatus().name(),
                publishedAt, totalPoints, questions.size(), draft.getDurationMinutes());
    }

    /**
     * Publish-time question-type shape validation against the pinned options +
     * numeric answerKey.
     */
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
    // Shared response assembly (GET detail + PUT composition)
    // ============================================================

    /** Builds the teacher editor response for an already-loaded, owned exam. */
    private TeacherExamEditorResponse buildEditorResponse(Exam exam) {
        Subject subject = subjectRepository.findById(exam.getSubjectId()).orElse(null);
        ExamPurpose purpose = exam.getPurposeId() != null
                ? purposeRepository.findById(exam.getPurposeId()).orElse(null)
                : null;
        ExamDraftVersionResponse draftResponse = loadDraft(exam.getId());
        List<PublishedExamVersionSummary> publishedSummaries = versionRepository
                .findAllByExamIdOrderByVersionNumberDesc(exam.getId()).stream()
                .filter(v -> v.getStatus() == ExamVersionStatus.PUBLISHED)
                .map(v -> new PublishedExamVersionSummary(
                        v.getVersionNumber(), v.getPublishedAt(), v.getTotalPoints()))
                .toList();
        return new TeacherExamEditorResponse(
                exam.getId(), exam.getCode(), exam.getTitle(), exam.getDescription(),
                subject != null ? new SubjectSummary(subject.getId(), subject.getCode(), subject.getName()) : null,
                purpose != null ? new ExamPurposeSummary(purpose.getId(), purpose.getCode(), purpose.getTitle()) : null,
                exam.getStatus().name(), exam.getCurrentVersionNumber(),
                draftResponse, publishedSummaries,
                exam.getCreatedAt(), exam.getUpdatedAt());
    }

    // ============================================================
    // Composition helpers
    // ============================================================

    /**
     * Cross-field uniqueness: section positions, per-section question positions,
     * source across draft.
     */
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
     * QuestionVersion,
     * and stages the new composition graph in memory. All source reads are batched
     * (questions, banks, versions, options) — no per-source query.
     */
    @SuppressWarnings("null")
    private List<StagedSection> resolveAndStageSources(UpdateDraftCompositionRequest request,
            Exam exam, Long draftVersionId) {
        List<Long> sourceQuestionIds = request.sections().stream()
                .flatMap(s -> s.questions().stream())
                .map(rq -> rq.sourceQuestionId())
                .distinct()
                .toList();

        if (sourceQuestionIds.isEmpty()) {
            // Empty composition: stage section layout only (may also be fully empty).
            List<StagedSection> staged = new ArrayList<>();
            for (var sec : request.sections()) {
                ExamSection section = new ExamSection(draftVersionId, sec.title(), sec.position());
                section.setInstructions(sec.instructions());
                staged.add(new StagedSection(section, List.of()));
            }
            return staged;
        }

        Map<Long, Question> questions = sourceQuestionRepository.findAllById(sourceQuestionIds).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        for (Long id : sourceQuestionIds) {
            if (!questions.containsKey(id)) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // missing source question
            }
        }

        Set<Long> bankIds = questions.values().stream()
                .map(Question::getQuestionBankId).collect(Collectors.toSet());
        Map<Long, QuestionBank> banks = sourceBankRepository.findAllById(bankIds).stream()
                .collect(Collectors.toMap(QuestionBank::getId, b -> b));
        for (Question q : questions.values()) {
            if (q.getStatus() != QuestionStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // inactive question
            }
            QuestionBank bank = banks.get(q.getQuestionBankId());
            if (bank == null || bank.getStatus() != QuestionBankStatus.ACTIVE) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // missing/inactive bank
            }
            // Source ownership: bank owner + school must match the exam. The
            // bank's subject is metadata only — a teacher may compose an exam
            // from any of their own banks (e.g. a mixed-topic quiz), so the
            // exam subject need not equal the bank subject.
            if (!bank.getOwnerTeacherId().equals(exam.getOwnerTeacherId())
                    || !bank.getSchoolId().equals(exam.getSchoolId())) {
                throw new ExamException(ExamErrorCode.EXAM_ACCESS_DENIED); // foreign/cross-school
            }
        }

        // Pin each question's current version (single batch query; pick by
        // currentVersionNumber).
        Map<Long, QuestionVersion> currentVersions = new HashMap<>();
        for (QuestionVersion v : sourceVersionRepository.findAllByQuestionIdIn(sourceQuestionIds)) {
            Question q = questions.get(v.getQuestionId());
            if (q != null && v.getVersionNumber().equals(q.getCurrentVersionNumber())) {
                currentVersions.put(v.getQuestionId(), v);
            }
        }
        for (Long id : sourceQuestionIds) {
            if (!currentVersions.containsKey(id)) {
                throw new ExamException(ExamErrorCode.EXAM_VALIDATION_ERROR); // no current version
            }
        }

        // Batch-load options for all pinned versions (deepCopy preserves numeric
        // representation).
        List<Long> pinnedVersionIds = currentVersions.values().stream().map(QuestionVersion::getId).toList();
        Map<Long, List<QuestionOption>> optionsByVersion = sourceOptionRepository
                .findAllByQuestionVersionIdInOrderByQuestionVersionIdAscPositionAsc(pinnedVersionIds).stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionVersionId));

        // Stage the new graph (entities not yet persisted; ids assigned at replace
        // time).
        List<StagedSection> staged = new ArrayList<>();
        for (var sec : request.sections()) {
            ExamSection section = new ExamSection(draftVersionId, sec.title(), sec.position());
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

    /**
     * Atomic DRAFT composition replace: delete old graph (FK-safe order) then
     * insert new graph.
     */
    @SuppressWarnings("null")
    private void replaceDraftComposition(Long draftVersionId, List<StagedSection> staged) {
        // Delete old DRAFT graph in FK-safe order: options -> questions -> sections.
        List<Long> oldQuestionIds = questionRepository.findAllByExamVersionId(draftVersionId).stream()
                .map(ExamQuestion::getId).filter(Objects::nonNull).toList();
        if (!oldQuestionIds.isEmpty()) {
            optionRepository.deleteAllByExamQuestionIdIn(oldQuestionIds);
            optionRepository.flush();
        }
        questionRepository.deleteAllByExamVersionId(draftVersionId);
        questionRepository.flush();
        sectionRepository.deleteAllByExamVersionId(draftVersionId);
        sectionRepository.flush();

        // Insert new graph: sections -> questions -> options.
        for (StagedSection ss : staged) {
            ExamSection savedSection = sectionRepository.saveAndFlush(ss.section());
            for (ResolvedQuestion rq : ss.questions()) {
                ExamQuestion eq = new ExamQuestion(draftVersionId, savedSection.getId(),
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

    // Resolved-source + staged-graph holders (ids assigned at persist time).
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
    private ExamDraftVersionResponse loadDraft(Long examId) {
        Optional<ExamVersion> draft = versionRepository.findFirstByExamIdAndStatus(examId, ExamVersionStatus.DRAFT);
        if (draft.isEmpty()) {
            return null;
        }
        ExamVersion dv = draft.get();
        Long versionId = dv.getId();

        // Batch: sections -> questions -> options
        List<ExamSection> sections = sectionRepository.findAllByExamVersionIdOrderByPositionAsc(versionId);
        List<Long> sectionIds = sections.stream().map(ExamSection::getId).toList();

        List<ExamQuestion> questions = sectionIds.isEmpty()
                ? List.of()
                : questionRepository.findAllByExamSectionIdInOrderByExamSectionIdAscPositionAsc(sectionIds);
        List<Long> questionIds = questions.stream().map(ExamQuestion::getId).toList();

        List<ExamQuestionOption> options = questionIds.isEmpty()
                ? List.of()
                : optionRepository.findAllByExamQuestionIdInOrderByExamQuestionIdAscPositionAsc(questionIds);

        // Group questions by section, options by question
        Map<Long, List<ExamQuestionOption>> optionsByQuestion = options.stream()
                .collect(Collectors.groupingBy(ExamQuestionOption::getExamQuestionId));
        Map<Long, List<ExamQuestion>> questionsBySection = questions.stream()
                .collect(Collectors.groupingBy(ExamQuestion::getExamSectionId));

        List<ExamSectionResponse> sectionResponses = sections.stream()
                .map(s -> new ExamSectionResponse(
                        s.getId(), s.getPosition(), s.getTitle(), s.getInstructions(),
                        questionsBySection.getOrDefault(s.getId(), List.of()).stream()
                                .map(q -> mapQuestion(q, optionsByQuestion.getOrDefault(q.getId(), List.of())))
                                .toList()))
                .toList();

        return new ExamDraftVersionResponse(
                dv.getVersionNumber(), dv.getStatus().name(),
                dv.getDurationMinutes(), dv.getInstructions(),
                deepCopy(dv.getTfMatrixScoring()),
                sectionResponses);
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
        if (ids.isEmpty())
            return Map.of();
        return subjectRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Subject::getId,
                        s -> new SubjectSummary(s.getId(), s.getCode(), s.getName())));
    }

    @SuppressWarnings("null")
    private Map<Long, ExamPurposeSummary> batchPurposes(List<Exam> exams) {
        Set<Long> ids = exams.stream().map(Exam::getPurposeId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty())
            return Map.of();
        return purposeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ExamPurpose::getId,
                        p -> new ExamPurposeSummary(p.getId(), p.getCode(), p.getTitle())));
    }

    private static boolean isCodeConflict(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.contains(CODE_CONFLICT_CONSTRAINT)) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}
