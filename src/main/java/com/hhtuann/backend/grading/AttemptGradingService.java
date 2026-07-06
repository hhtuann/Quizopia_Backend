package com.hhtuann.backend.grading;

import com.hhtuann.backend.attempt.domain.model.Attempt;
import com.hhtuann.backend.attempt.domain.model.AttemptAnswer;
import com.hhtuann.backend.attempt.domain.model.AttemptQuestion;
import com.hhtuann.backend.attempt.domain.model.Grade;
import com.hhtuann.backend.attempt.domain.model.GradeItem;
import com.hhtuann.backend.attempt.repository.AttemptAnswerRepository;
import com.hhtuann.backend.attempt.repository.AttemptQuestionRepository;
import com.hhtuann.backend.attempt.repository.GradeItemRepository;
import com.hhtuann.backend.attempt.repository.GradeRepository;
import com.hhtuann.backend.exam.domain.model.ExamQuestion;
import com.hhtuann.backend.exam.domain.model.ExamQuestionOption;
import com.hhtuann.backend.exam.repository.ExamQuestionOptionRepository;
import com.hhtuann.backend.exam.repository.ExamQuestionRepository;
import com.hhtuann.backend.grading.domain.AttemptGrade;
import com.hhtuann.backend.grading.domain.QuestionGrade;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Day 8 grading orchestration: batch-loads an attempt's snapshot + answer keys
 * + submitted answers, calls the
 * pure {@link Grader} per question, aggregates, and persists exactly one
 * {@link Grade} + one {@link GradeItem}
 * per attempt question. MUST run inside the submit transaction (after the
 * attempt transitions to SUBMITTED);
 * declared {@link Propagation#REQUIRED} so it joins the caller's tx and any
 * {@link GradingException} rolls the
 * whole submit (status + grade + items) back atomically.
 *
 * <p>
 * <b>No N+1:</b> four batch loads only — attempt questions (ordered), answers
 * (by attempt), exam questions
 * (by the attempt's pinned {@code exam_version_id}), options (by the attempt's
 * exam-question ids). No answer key
 * is logged or placed in {@code gradingDetails}; {@code gradingDetails} is an
 * empty object (safe metadata only).
 */
@Service
public class AttemptGradingService {

    private final AttemptQuestionRepository attemptQuestionRepo;
    private final AttemptAnswerRepository attemptAnswerRepo;
    private final ExamQuestionRepository examQuestionRepo;
    private final ExamQuestionOptionRepository examQuestionOptionRepo;
    private final GradeRepository gradeRepo;
    private final GradeItemRepository gradeItemRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttemptGradingService(AttemptQuestionRepository attemptQuestionRepo,
            AttemptAnswerRepository attemptAnswerRepo,
            ExamQuestionRepository examQuestionRepo,
            ExamQuestionOptionRepository examQuestionOptionRepo,
            GradeRepository gradeRepo, GradeItemRepository gradeItemRepo,
            ObjectMapper objectMapper, Clock clock) {
        this.attemptQuestionRepo = attemptQuestionRepo;
        this.attemptAnswerRepo = attemptAnswerRepo;
        this.examQuestionRepo = examQuestionRepo;
        this.examQuestionOptionRepo = examQuestionOptionRepo;
        this.gradeRepo = gradeRepo;
        this.gradeItemRepo = gradeItemRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Grades the attempt and persists the immutable {@link Grade} +
     * {@link GradeItem}s. Returns the persisted
     * Grade (its id is set after flush). Throws {@link GradingException} on any
     * configuration/data inconsistency
     * → the caller's transaction rolls back (no partial grade/items, no SUBMITTED
     * transition survives).
     */
    @SuppressWarnings("null")
    public Grade gradeAndPersist(Attempt attempt) {
        Long attemptId = attempt.getId();
        List<AttemptQuestion> questions = attemptQuestionRepo.findByAttemptIdOrderByDisplayOrderAsc(attemptId);
        if (questions.isEmpty()) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "attempt has no questions");
        }
        // Batch 1: answers keyed by attempt_question_id.
        Map<Long, AttemptAnswer> answerByQuestion = attemptAnswerRepo.findByAttemptId(attemptId).stream()
                .collect(Collectors.toMap(AttemptAnswer::getAttemptQuestionId, a -> a, (a, b) -> b));
        // Batch 2: exam questions for the attempt's pinned version (cross-version-safe
        // snapshot source).
        Map<Long, ExamQuestion> examQuestionById = examQuestionRepo.findAllByExamVersionId(attempt.getExamVersionId())
                .stream()
                .collect(Collectors.toMap(ExamQuestion::getId, e -> e));
        // Batch 3: options for the attempt's exam-question ids.
        List<Long> examQuestionIds = questions.stream().map(AttemptQuestion::getExamQuestionId).toList();
        Map<Long, List<ExamQuestionOption>> optionsByExamQuestion = examQuestionOptionRepo
                .findAllByExamQuestionIdInOrderByExamQuestionIdAscPositionAsc(examQuestionIds).stream()
                .collect(Collectors.groupingBy(ExamQuestionOption::getExamQuestionId));

        List<QuestionGrade> itemResults = new ArrayList<>(questions.size());
        for (AttemptQuestion q : questions) {
            ExamQuestion eq = examQuestionById.get(q.getExamQuestionId());
            if (eq == null) {
                throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT,
                        "attempt question references unknown exam question");
            }
            List<ExamQuestionOption> opts = optionsByExamQuestion.getOrDefault(q.getExamQuestionId(), List.of());
            AttemptAnswer answer = answerByQuestion.get(q.getId());
            JsonNode payload = answer == null ? null : answer.getAnswerPayload();
            itemResults.add(gradeQuestion(q.getQuestionType(), q.getDefaultPoints(), eq, opts, payload));
        }
        AttemptGrade aggregate = Grader.aggregate(itemResults);

        Instant gradedAt = Instant.now(clock);
        Grade grade = new Grade(attemptId, aggregate.score(), aggregate.score(), aggregate.maxScore(), gradedAt, null);
        grade.setPercentage(aggregate.percentage());
        grade = gradeRepo.save(grade);
        // No explicit flush here: with IDENTITY generation, save() triggers the INSERT
        // immediately
        // (grade.getId() is populated). The single flush in AttemptSubmitService's
        // cache-insert path
        // enforces ALL constraints (uk_grades_attempt, uk_idempotency_*, etc.) in one
        // pass — avoiding
        // a corrupted Hibernate session from a failed flush after prior successful
        // flushes.

        JsonNode emptyDetails = objectMapper.createObjectNode();
        for (int i = 0; i < questions.size(); i++) {
            AttemptQuestion q = questions.get(i);
            QuestionGrade g = itemResults.get(i);
            gradeItemRepo.save(new GradeItem(grade.getId(), attemptId, q.getId(),
                    g.awardedScore(), g.maxScore(), g.correct(), emptyDetails));
        }
        return grade;
    }

    private QuestionGrade gradeQuestion(String type, BigDecimal maxScore, ExamQuestion eq,
            List<ExamQuestionOption> opts, JsonNode payload) {
        return switch (type) {
            case "SINGLE_CHOICE" ->
                Grader.gradeSingle(maxScore, singleCorrectKey(opts), textField(payload, "selectedOptionKey"));
            case "MULTIPLE_CHOICE" -> Grader.gradeMultiple(maxScore, multipleCorrectKeys(opts), selectedKeys(payload));
            case "TRUE_FALSE_MATRIX" -> Grader.gradeMatrix(maxScore, matrixKey(opts), matrixStudent(payload));
            case "NUMERIC_FILL" -> Grader.gradeNumeric(maxScore, expectedNumeric(eq), numericValue(payload));
            default -> throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "unknown question type");
        };
    }

    @SuppressWarnings("null")
    private String singleCorrectKey(List<ExamQuestionOption> opts) {
        List<String> correct = opts.stream().filter(o -> Boolean.TRUE.equals(o.getIsCorrect()))
                .map(ExamQuestionOption::getOptionKey).toList();
        if (correct.size() != 1) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT,
                    "single-choice must have exactly one correct option");
        }
        return correct.get(0);
    }

    @SuppressWarnings("null")
    private Set<String> multipleCorrectKeys(List<ExamQuestionOption> opts) {
        Set<String> correct = opts.stream().filter(o -> Boolean.TRUE.equals(o.getIsCorrect()))
                .map(ExamQuestionOption::getOptionKey).collect(Collectors.toSet());
        if (correct.isEmpty()) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT,
                    "multiple-choice must have at least one correct option");
        }
        return correct;
    }

    private Map<String, Boolean> matrixKey(List<ExamQuestionOption> opts) {
        Map<String, Boolean> key = new LinkedHashMap<>();
        for (ExamQuestionOption o : opts) {
            key.put(o.getOptionKey(), Boolean.TRUE.equals(o.getIsCorrect()));
        }
        if (key.isEmpty()) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "matrix must have statements");
        }
        return key;
    }

    private Map<String, Boolean> matrixStudent(JsonNode payload) {
        if (payload == null) {
            return Map.of();
        }
        JsonNode responses = payload.get("responses");
        if (responses == null || !responses.isObject()) {
            return Map.of();
        }
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : responses.properties()) {
            JsonNode v = e.getValue();
            if (v != null && v.isBoolean()) {
                map.put(e.getKey(), v.booleanValue());
            }
        }
        return map;
    }

    private Set<String> selectedKeys(JsonNode payload) {
        if (payload == null) {
            return Set.of();
        }
        JsonNode arr = payload.get("selectedOptionKeys");
        if (arr == null || !arr.isArray()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (JsonNode n : arr) {
            if (n != null && n.isString()) {
                String k = n.asString();
                if (!k.isBlank()) {
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    private String textField(JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonNode n = payload.get(field);
        return (n == null || n.isNull()) ? null : n.asString();
    }

    private BigDecimal expectedNumeric(ExamQuestion eq) {
        JsonNode key = eq.getAnswerKey();
        if (key == null || !key.isObject()) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "numeric-fill missing answer key");
        }
        JsonNode node = key.get("expectedAnswer");
        if (node == null || !node.isString()) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT,
                    "numeric-fill missing expectedAnswer");
        }
        return parseNumeric(node.asString());
    }

    private BigDecimal numericValue(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get("value");
        return (node == null || node.isNull() || !node.isString()) ? null : parseNumeric(node.asString());
    }

    /**
     * Parses a stored numeric string to BigDecimal (comma → dot per Day 7 grading
     * rule; never double).
     */
    private static BigDecimal parseNumeric(String raw) {
        try {
            return new BigDecimal(raw.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "numeric-fill unparseable value");
        }
    }
}
