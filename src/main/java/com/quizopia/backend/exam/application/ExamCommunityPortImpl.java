package com.quizopia.backend.exam.application;

import com.quizopia.backend.common.BusinessCodes;
import com.quizopia.backend.exam.domain.model.Exam;
import com.quizopia.backend.exam.domain.model.ExamQuestion;
import com.quizopia.backend.exam.domain.model.ExamQuestionOption;
import com.quizopia.backend.exam.domain.model.ExamSection;
import com.quizopia.backend.exam.domain.model.ExamStatus;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.exam.repository.ExamQuestionOptionRepository;
import com.quizopia.backend.exam.repository.ExamQuestionRepository;
import com.quizopia.backend.exam.repository.ExamRepository;
import com.quizopia.backend.exam.repository.ExamSectionRepository;
import com.quizopia.backend.question.application.QuestionCommunityPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link ExamCommunityPort} implementation (ADR-006 §6). The
 * eligibility projection is read-only (one query, no locks, no
 * content). The fork surface (snapshot load + DRAFT creation) joins
 * the COMMUNITY fork transaction — never opens its own — so §8's
 * single-transaction atomicity holds:
 * {@link #loadPublishedSnapshot} takes the source exam row lock
 * ({@code findByIdForUpdate}, the §8 lock-order head) and then reads
 * the immutable composition rows; {@link #createForkDraft} writes the
 * cloned DRAFT with remapped provenance ids. Composition inserts
 * follow the {@code saveAndFlush} discipline of
 * {@code ExamService.deepCopyComposition}.
 */
@Component
public class ExamCommunityPortImpl implements ExamCommunityPort {

    private static final String CODE_CONFLICT_CONSTRAINT = "uk_exams_creator_code_ci";

    private final JdbcTemplate jdbc;
    private final ExamRepository examRepository;
    private final ExamSectionRepository sectionRepository;
    private final ExamQuestionRepository questionRepository;
    private final ExamQuestionOptionRepository optionRepository;

    public ExamCommunityPortImpl(JdbcTemplate jdbc,
            ExamRepository examRepository,
            ExamSectionRepository sectionRepository,
            ExamQuestionRepository questionRepository,
            ExamQuestionOptionRepository optionRepository) {
        this.jdbc = jdbc;
        this.examRepository = examRepository;
        this.sectionRepository = sectionRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublicationEligibility> checkPublicationEligibility(Long examId, Long userId) {
        List<PublicationEligibility> rows = jdbc.query("""
                        SELECT e.created_by_user_id AS exam_owner_id,
                               e.status             AS exam_status,
                               COALESCE(e.duration_minutes, 0) AS duration_minutes,
                               (SELECT count(*) FROM exam_sections s WHERE s.exam_id = e.id)  AS section_count,
                               (SELECT count(*) FROM exam_questions q WHERE q.exam_id = e.id) AS question_count
                        FROM exams e
                        WHERE e.id = ? AND e.created_by_user_id = ?
                        """,
                (rs, i) -> new PublicationEligibility(
                        rs.getLong("exam_owner_id"),
                        ExamStatus.valueOf(rs.getString("exam_status")),
                        rs.getInt("duration_minutes"),
                        rs.getInt("section_count"),
                        rs.getInt("question_count")),
                examId, userId);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<PublishedCompositionSnapshot> loadPublishedSnapshot(Long examId) {
        // §8 step 3: the source exam row lock comes FIRST (before the
        // publication lock); the composition rows are then read inside
        // that lock — the immutable published snapshot, never the
        // mutable question_versions.
        Optional<Exam> locked = examRepository.findByIdForUpdate(examId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        Exam exam = locked.get();

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
                .collect(Collectors.groupingBy(ExamQuestion::getExamSectionId,
                        Collectors.collectingAndThen(Collectors.toList(), l -> {
                            l.sort(Comparator.comparing(ExamQuestion::getPosition));
                            return l;
                        })));

        List<SnapshotSection> snapshotSections = sections.stream()
                .map(s -> new SnapshotSection(
                        s.getPosition() == null ? 0 : s.getPosition(),
                        s.getTitle(), s.getInstructions(), s.getCode(),
                        questionsBySection.getOrDefault(s.getId(), List.of()).stream()
                                .map(q -> new SnapshotQuestion(
                                        q.getPosition() == null ? 0 : q.getPosition(),
                                        q.getQuestionType(), q.getContent(), q.getDefaultPoints(),
                                        q.getDifficulty(), q.getExplanation(),
                                        deepCopy(q.getAnswerKey()), deepCopy(q.getMetadata()),
                                        q.getSourceQuestionId(), q.getSourceQuestionVersionId(),
                                        optionsByQuestion.getOrDefault(q.getId(), List.of()).stream()
                                                .map(o -> new SnapshotOption(o.getOptionKey(), o.getContent(),
                                                        o.getIsCorrect() != null && o.getIsCorrect(),
                                                        o.getPosition() == null ? 0 : o.getPosition()))
                                                .toList()))
                                .toList()))
                .toList();

        return Optional.of(new PublishedCompositionSnapshot(
                deepCopy(exam.getTfMatrixScoring()), exam.getInstructions(), exam.getDurationMinutes(),
                exam.getSubjectId(), exam.getPurposeId(), exam.getTitle(), exam.getDescription(),
                snapshotSections));
    }

    @Override
    @Transactional
    public Long createForkDraft(ForkDraftSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        PublishedCompositionSnapshot snapshot = spec.snapshot();

        Exam draft = new Exam(spec.ownerUserId(), snapshot.subjectId(),
                BusinessCodes.readableCode("EX", 8,
                        c -> examRepository.existsByCreatedByUserUserIdAndCodeIgnoreCase(
                                spec.ownerUserId(), c)),
                snapshot.title());
        draft.setDescription(snapshot.description());
        draft.setPurposeId(snapshot.purposeId());
        draft.updateDraftSettings(snapshot.durationMinutes(), snapshot.instructions());
        draft.copyGradingPolicyFrom(snapshot.tfMatrixScoring());
        draft.markAsDuplicateOf(spec.sourceExamId());
        try {
            draft = examRepository.saveAndFlush(draft);
        } catch (DataIntegrityViolationException ex) {
            if (isCodeConflict(ex)) {
                throw new ExamException(ExamErrorCode.EXAM_CODE_CONFLICT);
            }
            throw ex;
        }

        for (SnapshotSection section : snapshot.sections()) {
            ExamSection newSection = new ExamSection(draft.getId(), section.title(), section.position());
            newSection.setCode(section.code());
            newSection.setInstructions(section.instructions());
            ExamSection savedSection = sectionRepository.saveAndFlush(newSection);
            for (SnapshotQuestion question : section.questions()) {
                QuestionCommunityPort.ClonedQuestion remapped =
                        spec.remappedQuestions().get(question.sourceQuestionId());
                if (remapped == null) {
                    // Defensive: the clone step must have covered every
                    // snapshot question (§8 steps 5-7 run in one unit).
                    throw new IllegalStateException(
                            "Fork remapping missing for source question " + question.sourceQuestionId());
                }
                ExamQuestion newQuestion = new ExamQuestion(draft.getId(), savedSection.getId(),
                        remapped.questionId(), remapped.versionId(), remapped.code(),
                        question.questionType(), question.content(), question.points(),
                        question.position());
                newQuestion.setDifficulty(question.difficulty());
                newQuestion.setExplanation(question.explanation());
                newQuestion.setAnswerKey(deepCopy(question.answerKey()));
                newQuestion.setMetadata(deepCopy(question.metadata()));
                ExamQuestion savedQuestion = questionRepository.saveAndFlush(newQuestion);
                if (!question.options().isEmpty()) {
                    optionRepository.saveAll(question.options().stream()
                            .map(o -> new ExamQuestionOption(savedQuestion.getId(), o.optionKey(),
                                    o.content(), o.isCorrect(), o.position()))
                            .toList());
                    optionRepository.flush();
                }
            }
        }
        return draft.getId();
    }

    /** Never alias snapshot JSON into another persisted row. */
    private static JsonNode deepCopy(JsonNode node) {
        return node != null ? node.deepCopy() : null;
    }

    @SuppressWarnings("deprecation")
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
