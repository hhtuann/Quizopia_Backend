package com.quizopia.backend.attempt.application;

import com.quizopia.backend.academic.domain.model.StudentProfile;
import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.domain.model.AttemptQuestion;
import com.quizopia.backend.attempt.domain.model.AttemptStatus;
import com.quizopia.backend.attempt.dto.AvailableSessionsResponse;
import com.quizopia.backend.attempt.dto.AvailableSessionsResponse.AvailableSessionItem;
import com.quizopia.backend.attempt.dto.AvailableSessionsResponse.AvailableSessionItem.ExamInfo;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.dto.StartAttemptResponse;
import com.quizopia.backend.attempt.dto.StartAttemptResponse.QuestionView;
import com.quizopia.backend.attempt.dto.StartAttemptResponse.QuestionView.OptionView;
import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.attempt.repository.AttemptQuestionRepository;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.exam.domain.model.ExamSession;
import com.quizopia.backend.exam.domain.model.ExamSessionStatus;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.realtime.event.AttemptRealtimeEvent;
import com.quizopia.backend.realtime.event.RealtimeEventType;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Application service for the attempt module: available-sessions query +
 * start/resume.
 *
 * <p>
 * <b>Lock order</b> (start): session → attempt. Classes replace participants
 * (V10) — visibility/class-membership is the eligibility gate, so there is no
 * participant row lock.
 * <p>
 * <b>No answer leak:</b> the available and start responses never expose
 * answerKey,
 * isCorrect, explanation, TF ladder, or score.
 * <p>
 * <b>No WebSocket</b> in this checkpoint — events are pending the realtime
 * integration.
 */
@Service
@Transactional(readOnly = true)
public class AttemptService {

    private static final Logger log = LoggerFactory.getLogger(AttemptService.class);

    private final AttemptAuthorizationService auth;
    private final AttemptRepository attemptRepo;
    private final AttemptQuestionRepository aqRepo;
    private final ExamSessionRepository sessionRepo;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public AttemptService(AttemptAuthorizationService auth, AttemptRepository attemptRepo,
            AttemptQuestionRepository aqRepo, ExamSessionRepository sessionRepo,
            JdbcTemplate jdbc, EntityManager em, Clock clock,
            ApplicationEventPublisher eventPublisher) {
        this.auth = auth;
        this.attemptRepo = attemptRepo;
        this.aqRepo = aqRepo;
        this.sessionRepo = sessionRepo;
        this.jdbc = jdbc;
        this.em = em;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    // ============================================================
    // AVAILABLE SESSIONS — single native query, no N+1
    // ============================================================

    public AvailableSessionsResponse getAvailableSessions(Long userId) {
        StudentProfile profile = auth.requireStudentWithPermission(userId, "EXAM_SESSION_READ");
        Instant now = Instant.now(clock);
        List<AvailableSessionItem> items = queryAvailable(profile.getSchoolId(), profile.getId(), now);
        return new AvailableSessionsResponse(items, now);
    }

    private List<AvailableSessionItem> queryAvailable(Long schoolId, Long studentId, Instant now) {
        // Class-based visibility (replaces the legacy exam_session_participants JOIN):
        // a session is visible if PUBLIC (same school) OR the student is a member of at
        // least one classroom assigned to the session via exam_session_classes.
        String sql = "SELECT s.id, s.code, s.title, s.status, s.visibility, s.starts_at, s.ends_at, s.max_attempts, "
                + "e.id AS exam_id, e.title AS exam_title, subj.name AS subject_name, "
                + "COALESCE(s.duration_minutes, ev.duration_minutes) AS duration_minutes, "
                + "(SELECT COUNT(*) FROM attempts a WHERE a.exam_session_id = s.id AND a.student_profile_id = ?) AS used, "
                + "act.id AS active_id, act.deadline_at AS active_deadline "
                + "FROM exam_sessions s "
                + "JOIN exam_versions ev ON ev.id = s.exam_version_id "
                + "JOIN exams e ON e.id = ev.exam_id "
                + "JOIN subjects subj ON subj.id = e.subject_id "
                + "LEFT JOIN attempts act ON act.exam_session_id = s.id AND act.student_profile_id = ? AND act.status = 'IN_PROGRESS' "
                + "WHERE s.school_id = ? AND s.status IN ('SCHEDULED','OPEN') "
                + "AND (s.visibility = 'PUBLIC' OR EXISTS ("
                + "  SELECT 1 FROM exam_session_classes esc "
                + "  JOIN classroom_members cm ON cm.classroom_id = esc.classroom_id "
                + "  WHERE esc.exam_session_id = s.id AND cm.student_profile_id = ?)) "
                + "ORDER BY s.starts_at ASC, s.id ASC";
        return jdbc.query(sql, (rs, n) -> {
            int maxAttempts = rs.getInt("max_attempts");
            int used = rs.getInt("used");
            // 0 = unlimited: remaining is effectively infinite.
            int remaining = maxAttempts == 0 ? Integer.MAX_VALUE : Math.max(0, maxAttempts - used);
            Long activeId = (Long) rs.getObject("active_id");
            Instant activeDeadline = rs.getTimestamp("active_deadline") != null
                    ? rs.getTimestamp("active_deadline").toInstant()
                    : null;
            String status = rs.getString("status");
            Instant startsAt = rs.getTimestamp("starts_at").toInstant();
            Instant endsAt = rs.getTimestamp("ends_at").toInstant();
            boolean canResume = activeId != null && activeDeadline != null && !now.isAfter(activeDeadline);
            boolean canStartNow = activeId == null
                    && ("OPEN".equals(status) || "SCHEDULED".equals(status))
                    && !now.isBefore(startsAt) && !now.isAfter(endsAt) && remaining > 0;
            return new AvailableSessionItem(
                    rs.getLong("id"), rs.getString("code"), rs.getString("title"), status,
                    new ExamInfo(rs.getLong("exam_id"), rs.getString("exam_title"), rs.getString("subject_name")),
                    startsAt, endsAt, rs.getInt("duration_minutes"), maxAttempts,
                    used, remaining, activeId, activeDeadline, canStartNow, canResume,
                    rs.getString("visibility"));
        }, studentId, studentId, schoolId, studentId);
    }

    // ============================================================
    // START / RESUME ATTEMPT
    // ============================================================

    @Transactional
    public StartAttemptResponse startAttempt(Long userId, Long sessionId, StartAttemptRequest request) {
        StudentProfile profile = auth.requireStudentWithPermission(userId, "ATTEMPT_START");
        Instant now = Instant.now(clock);

        // 1. Lock session (pessimistic write). Lock order is now session → attempt
        // (the legacy participant row lock is gone — class membership is the gatekeeper).
        ExamSession session = sessionRepo.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new AttemptException(AttemptErrorCode.EXAM_SESSION_NOT_FOUND));

        // 2. Visibility-based eligibility (replaces the legacy participant ELIGIBLE check):
        // PUBLIC → same school; CLASS_RESTRICTED → member of an assigned class.
        if (!isEligible(profile, session)) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_NOT_ELIGIBLE);
        }
        // 2b. Lazy-open: SCHEDULED within the time window → OPEN (auto-open on
        // student access — no manual teacher action needed).
        if (session.getStatus() == ExamSessionStatus.SCHEDULED
                && !now.isBefore(session.getStartsAt()) && !now.isAfter(session.getEndsAt())) {
            session.open(now);
            sessionRepo.saveAndFlush(session);
        }
        // 3. Check session OPEN.
        if (session.getStatus() != ExamSessionStatus.OPEN) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_SESSION_NOT_OPEN);
        }
        // 4. Check time window [startsAt, endsAt].
        if (now.isBefore(session.getStartsAt()) || now.isAfter(session.getEndsAt())) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_OUTSIDE_WINDOW);
        }

        // 5. Find active attempt under session lock.
        Optional<Attempt> active = attemptRepo.findByExamSessionIdAndStudentProfileIdAndStatus(
                sessionId, profile.getId(), AttemptStatus.IN_PROGRESS);
        if (active.isPresent()) {
            return resumeOrRejectExpired(active.get(), now, session);
        }

        // 6. Create new attempt.
        return createNewAttempt(profile, session, now, request);
    }

    /**
     * Class-based eligibility gate (replaces the participant ELIGIBLE check).
     * <ul>
     *   <li>PUBLIC: any student in the SAME school.</li>
     *   <li>CLASS_RESTRICTED: student is a member of at least one classroom
     *       assigned to the session via {@code exam_session_classes}.</li>
     * </ul>
     */
    private boolean isEligible(StudentProfile profile, ExamSession session) {
        if (!java.util.Objects.equals(profile.getSchoolId(), session.getSchoolId())) {
            return false;
        }
        if (session.getVisibility() == com.quizopia.backend.exam.domain.model.SessionVisibility.PUBLIC) {
            return true;
        }
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_session_classes esc "
                        + "JOIN classroom_members cm ON cm.classroom_id = esc.classroom_id "
                        + "WHERE esc.exam_session_id = ? AND cm.student_profile_id = ?",
                Integer.class, session.getId(), profile.getId());
        return cnt != null && cnt > 0;
    }

    private StartAttemptResponse resumeOrRejectExpired(Attempt active, Instant now, ExamSession session) {
        if (now.isAfter(active.getDeadlineAt())) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_DEADLINE_EXCEEDED);
        }
        // Resume: no new attempt, no new questions, no event.
        List<AttemptQuestion> questions = aqRepo.findByAttemptIdOrderByDisplayOrderAsc(active.getId());
        List<QuestionView> qv = buildQuestionViews(questions);
        return new StartAttemptResponse(active.getId(), session.getId(), active.getAttemptNumber(),
                active.getStatus().name(), active.getStartedAt(), active.getDeadlineAt(), now,
                true, session.getMaxAttempts(), qv);
    }

    private StartAttemptResponse createNewAttempt(StudentProfile profile, ExamSession session,
            Instant now, StartAttemptRequest request) {
        // MAX+1 under session lock.
        int nextNumber = attemptRepo.findMaxAttemptNumber(session.getId(), profile.getId()).orElse(0) + 1;
        // 0 = unlimited: no max-attempts check.
        if (session.getMaxAttempts() > 0 && nextNumber > session.getMaxAttempts()) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_MAX_REACHED);
        }

        // Deadline = min(session.endsAt, startedAt + duration). Duration priority:
        // 1. session.durationMinutes (override) if non-null.
        // 2. exam version's durationMinutes.
        // 3. 0 = unlimited (deadline = session.endsAt).
        Integer sessionDuration = session.getDurationMinutes();
        int durationMinutes = sessionDuration != null ? sessionDuration : fetchDurationMinutes(session.getExamVersionId());
        Instant deadline = session.getEndsAt();
        if (durationMinutes > 0) {
            Instant durationEnd = now.plusSeconds(durationMinutes * 60L);
            if (durationEnd.isBefore(deadline)) {
                deadline = durationEnd;
            }
        }

        // Wrap ENTIRE creation+snapshot in error boundary (Fix B).
        Attempt attempt;
        try {
            attempt = Attempt.start(
                    session.getSchoolId(), session.getId(), profile.getId(), session.getExamVersionId(),
                    nextNumber, now, deadline, request.clientInstanceId());
            em.persist(attempt);
            em.flush();

            List<QuestionRow> qRows = queryOrderedQuestions(session.getExamVersionId());
            if (qRows.isEmpty()) {
                log.warn("exam version has no questions: versionId={}", session.getExamVersionId());
                throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
            }
            Map<Long, List<OptionRow>> optionsByQuestion = queryOptions(
                    qRows.stream().map(q -> q.examQuestionId).toList());
            int order = 0;
            for (QuestionRow q : qRows) {
                List<OptionRow> opts = optionsByQuestion.getOrDefault(q.examQuestionId, List.of());
                // Validate question-type ↔ source-option cardinality BEFORE snapshotting so an
                // invalid source snapshot aborts the whole transaction (0
                // attempt/questions/answers).
                validateSourceCardinality(q.questionType, opts.size());
                JsonNode optionOrder = opts.isEmpty() ? null : buildOptionOrderJson(opts);
                AttemptQuestion aq = new AttemptQuestion(attempt.getId(), q.examQuestionId, q.questionType,
                        q.defaultPoints, order++, optionOrder);
                em.persist(aq);
            }
            em.flush();
        } catch (DataIntegrityViolationException e) {
            throw classifyAndTranslate(e);
        } catch (jakarta.persistence.PersistenceException e) {
            throw classifyAndTranslate(e);
        }

        // Build response (reuse query rows for question content; no
        // answerKey/isCorrect).
        List<AttemptQuestion> persisted = aqRepo.findByAttemptIdOrderByDisplayOrderAsc(attempt.getId());
        List<QuestionView> qv = buildQuestionViews(persisted);
        // Publish ATTEMPT_STARTED within the tx — the broadcaster only delivers it
        // AFTER_COMMIT.
        // Resume / failed starts never reach this point, so no duplicate/rollback
        // event.
        eventPublisher.publishEvent(new AttemptRealtimeEvent(
                RealtimeEventType.ATTEMPT_STARTED, session.getId(), attempt.getId(), profile.getId(), now));
        return new StartAttemptResponse(attempt.getId(), session.getId(), nextNumber,
                AttemptStatus.IN_PROGRESS.name(), now, deadline, now,
                false, session.getMaxAttempts(), qv);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private int fetchDurationMinutes(Long examVersionId) {
        List<Integer> results = jdbc.queryForList(
                "SELECT duration_minutes FROM exam_versions WHERE id = ? AND status = 'PUBLISHED'",
                Integer.class, examVersionId);
        if (results.isEmpty()) {
            log.warn("exam version not found or not PUBLISHED: versionId={}", examVersionId);
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
        return results.get(0);
    }

    /**
     * Queries ordered exam_questions (section.position, question.position) for a
     * version.
     */
    private List<QuestionRow> queryOrderedQuestions(Long examVersionId) {
        return jdbc.query(
                "SELECT eq.id, eq.question_type, eq.content, eq.default_points "
                        + "FROM exam_questions eq JOIN exam_sections es ON es.id = eq.exam_section_id "
                        + "WHERE eq.exam_version_id = ? ORDER BY es.position ASC, eq.position ASC, eq.id ASC",
                (rs, n) -> new QuestionRow(
                        rs.getLong("id"), rs.getString("question_type"),
                        rs.getString("content"), rs.getBigDecimal("default_points")),
                examVersionId);
    }

    /** Batch-loads options ordered by position for a set of exam_question_ids. */
    private Map<Long, List<OptionRow>> queryOptions(List<Long> examQuestionIds) {
        if (examQuestionIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", examQuestionIds.stream().map(k -> "?").toList());
        List<OptionRow> all = jdbc.query(
                "SELECT id, exam_question_id, option_key, content, position "
                        + "FROM exam_question_options WHERE exam_question_id IN (" + placeholders + ") "
                        + "ORDER BY exam_question_id ASC, position ASC, option_key ASC, id ASC",
                (rs, n) -> new OptionRow(
                        rs.getLong("exam_question_id"), rs.getString("option_key"),
                        rs.getString("content"), rs.getInt("position")),
                examQuestionIds.toArray());
        Map<Long, List<OptionRow>> map = new HashMap<>();
        for (OptionRow o : all) {
            map.computeIfAbsent(o.examQuestionId, k -> new ArrayList<>()).add(o);
        }
        return map;
    }

    private JsonNode buildOptionOrderJson(List<OptionRow> opts) {
        var arr = JsonNodeFactory.instance.arrayNode();
        for (OptionRow o : opts) {
            arr.add(o.optionKey);
        }
        return arr;
    }

    /**
     * Builds QuestionView list from persisted AttemptQuestion entities + their exam
     * snapshot data.
     */
    @SuppressWarnings("null")
    private List<QuestionView> buildQuestionViews(List<AttemptQuestion> questions) {
        if (questions.isEmpty()) {
            return List.of();
        }
        // Fetch exam_question content for rendering (no answerKey/isCorrect).
        List<Long> eqIds = questions.stream().map(AttemptQuestion::getExamQuestionId).toList();
        Map<Long, String> contentById = new HashMap<>();
        jdbc.query(
                "SELECT id, content FROM exam_questions WHERE id IN ("
                        + String.join(",", eqIds.stream().map(k -> "?").toList()) + ")",
                (rs) -> {
                    contentById.put(rs.getLong("id"), rs.getString("content"));
                },
                eqIds.toArray());
        // Batch-load ALL option content by key for the questions in scope.
        Map<Long, Map<String, OptionRow>> optContentByQuestion = new HashMap<>();
        Map<Long, List<OptionRow>> optsById = queryOptions(eqIds);
        for (var entry : optsById.entrySet()) {
            Map<String, OptionRow> byKey = new HashMap<>();
            for (OptionRow o : entry.getValue()) {
                byKey.put(o.optionKey, o);
            }
            optContentByQuestion.put(entry.getKey(), byKey);
        }

        List<QuestionView> result = new ArrayList<>();
        for (AttemptQuestion aq : questions) {
            String content = contentById.get(aq.getExamQuestionId());
            // Build option views from the PERSISTED option_order JSON — NEVER from source
            // position.
            // The persisted snapshot is the single source of truth; a post-start mutation
            // of
            // exam_question_options.position cannot reorder a live attempt. The snapshot is
            // fully
            // validated (no silent skips): option-based questions require a valid persisted
            // order;
            // no-option questions (NUMERIC_FILL) must have a null persisted order.
            Map<String, OptionRow> contentByKey = optContentByQuestion.getOrDefault(
                    aq.getExamQuestionId(), Map.of());
            List<OptionView> optionViews = buildValidatedOptionViews(
                    aq, aq.getOptionOrder(), contentByKey, contentByKey.size());
            result.add(new QuestionView(aq.getId(), aq.getExamQuestionId(), aq.getQuestionType(),
                    aq.getDisplayOrder(), content, aq.getDefaultPoints(), optionViews));
        }
        return result;
    }

    /**
     * Question-type ↔ source-option cardinality invariant. Used at BOTH new-start
     * (against the
     * source {@code exam_question.question_type}) and resume (against the
     * denormalized
     * {@code attempt_question.question_type} + the current immutable source
     * options). Any mismatch
     * throws {@code ATTEMPT_VALIDATION_ERROR}:
     * <ul>
     * <li>NUMERIC_FILL → exactly 0 source options.</li>
     * <li>SINGLE_CHOICE / MULTIPLE_CHOICE → 4–6 source options.</li>
     * <li>TRUE_FALSE_MATRIX → exactly 4 source statements (keys A–D).</li>
     * </ul>
     */
    private static void validateSourceCardinality(String questionType, int sourceOptionCount) {
        switch (questionType) {
            case "NUMERIC_FILL":
                if (sourceOptionCount != 0) {
                    log.warn("NUMERIC_FILL question must have 0 source options but has {}", sourceOptionCount);
                    throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
                }
                return;
            case "SINGLE_CHOICE", "MULTIPLE_CHOICE":
                if (sourceOptionCount < 4 || sourceOptionCount > 6) {
                    log.warn("{} question must have 4-6 source options but has {}", questionType, sourceOptionCount);
                    throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
                }
                return;
            case "TRUE_FALSE_MATRIX":
                if (sourceOptionCount != 4) {
                    log.warn("TRUE_FALSE_MATRIX question must have exactly 4 source statements but has {}",
                            sourceOptionCount);
                    throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
                }
                return;
            default:
                log.warn("unknown question type '{}'", questionType);
                throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
    }

    /**
     * Validates the persisted option_order snapshot against the denormalized
     * question type and the
     * current immutable source options, then builds the ordered option views
     * (source position kept —
     * the START response renders source positions). Delegates the shared validation
     * to
     * {@link #validatedOrderedOptionKeys}.
     */
    private List<OptionView> buildValidatedOptionViews(AttemptQuestion aq, JsonNode persistedOrder,
            Map<String, OptionRow> contentByKey, int sourceOptionCount) {
        List<String> keys = validatedOrderedOptionKeys(aq.getQuestionType(), aq.getId(), persistedOrder,
                contentByKey.keySet());
        List<OptionView> optionViews = new ArrayList<>(keys.size());
        for (String key : keys) {
            OptionRow opt = contentByKey.get(key);
            optionViews.add(new OptionView(opt.optionKey, opt.content, opt.position));
        }
        return optionViews;
    }

    /**
     * Validates the persisted {@code option_order} snapshot and returns the option
     * keys in persisted
     * (render) order. Shared by start ({@link #buildValidatedOptionViews}) and the
     * detail endpoint
     * ({@code AttemptQueryService}), so the approved A3.2-1 validation (type-driven
     * cardinality +
     * persisted-order structural checks) is applied identically on every read.
     * Package-private so the
     * detail query service can reuse it without duplicating the rules.
     *
     * <p>
     * Rules: NUMERIC_FILL → 0 source options + null persisted order;
     * SINGLE_CHOICE/MULTIPLE_CHOICE
     * → 4–6 source options + required non-empty array of distinct string keys, all
     * present in source,
     * count match; TRUE_FALSE_MATRIX → exactly 4 source statements, keys ⊆ A–D,
     * count 4. Any
     * violation → {@code ATTEMPT_VALIDATION_ERROR} (400).
     */
    static List<String> validatedOrderedOptionKeys(String questionType, long aqId, JsonNode persistedOrder,
            java.util.Set<String> sourceKeys) {
        int sourceOptionCount = sourceKeys.size();
        validateSourceCardinality(questionType, sourceOptionCount);

        if ("NUMERIC_FILL".equals(questionType)) {
            if (persistedOrder != null) {
                log.warn("NUMERIC_FILL question has non-null optionOrder: aqId={}", aqId);
                throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
            }
            return List.of();
        }
        if (persistedOrder == null) {
            log.warn("option-based question [{}] has null optionOrder: aqId={}", questionType, aqId);
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
        if (!persistedOrder.isArray()) {
            log.warn("persisted optionOrder is not an array: aqId={}", aqId);
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
        if (persistedOrder.isEmpty()) {
            log.warn("persisted optionOrder is empty: aqId={}", aqId);
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
        java.util.Set<String> tfAllowed = "TRUE_FALSE_MATRIX".equals(questionType)
                ? java.util.Set.of("A", "B", "C", "D")
                : null;
        List<String> orderedKeys = new ArrayList<>(persistedOrder.size());
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        for (JsonNode keyNode : persistedOrder) {
            if (!keyNode.isString()) {
                log.warn("persisted optionOrder has non-string key: aqId={}", aqId);
                throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
            }
            String key = keyNode.asString();
            if (!seenKeys.add(key)) {
                log.warn("persisted optionOrder has duplicate key '{}': aqId={}", key, aqId);
                throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
            }
            if (tfAllowed != null && !tfAllowed.contains(key)) {
                log.warn("TRUE_FALSE_MATRIX persisted key '{}' outside A-D: aqId={}", key, aqId);
                throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
            }
            if (!sourceKeys.contains(key)) {
                log.warn("persisted optionOrder key '{}' not found in source: aqId={}", key, aqId);
                throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
            }
            orderedKeys.add(key);
        }
        if (orderedKeys.size() != sourceOptionCount) {
            log.warn("persisted optionOrder has {} entries but source has {}: aqId={}",
                    orderedKeys.size(), sourceOptionCount, aqId);
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
        return orderedKeys;
    }

    // V9 snapshot constraints allowlist (→ 400 VALIDATION_ERROR if violated).
    private static final java.util.Set<String> SNAPSHOT_CONSTRAINTS = java.util.Set.of(
            "fk_attempt_questions_attempt", "fk_attempt_questions_exam",
            "fk_attempt_answers_attempt", "fk_attempt_answers_question_attempt",
            "uk_attempt_questions_attempt_id", "uk_attempt_questions_attempt_exam",
            "uk_attempt_questions_attempt_order",
            "chk_attempt_questions_type", "chk_attempt_questions_points",
            "chk_attempt_questions_order", "chk_attempt_questions_option_order",
            "chk_attempt_answers_seq", "chk_attempt_answers_payload",
            "uk_attempt_answers_attempt_question",
            "chk_attempts_status", "chk_attempts_number", "chk_attempts_deadline",
            "chk_attempts_submission_invariant",
            "fk_attempts_session_school", "fk_attempts_student_school", "fk_attempts_version");

    private static final java.util.Set<String> DUPLICATE_CONSTRAINTS = java.util.Set.of(
            "uk_attempts_one_active_per_session_student",
            "uk_attempts_session_student_number");

    /**
     * Unified constraint classifier. Used by BOTH DataIntegrityViolationException
     * and PersistenceException catch blocks. Maps known constraints to public
     * error codes; rethrows unknown constraints to avoid hiding bugs.
     *
     * <p>
     * For an unknown constraint (or none extractable), the ORIGINAL exception is
     * rethrown so
     * the real failure surfaces — it is never masked as
     * {@code ATTEMPT_VALIDATION_ERROR}. The
     * constraint name, SQL, and class name are logged server-side only and never
     * reach the
     * client (GlobalExceptionHandler returns a generic INTERNAL_ERROR body).
     */
    private static RuntimeException classifyAndTranslate(Throwable e) {
        String cn = extractConstraintName(e);
        if (DUPLICATE_CONSTRAINTS.contains(cn)) {
            return new AttemptException(AttemptErrorCode.ATTEMPT_DUPLICATE_START);
        }
        if (SNAPSHOT_CONSTRAINTS.contains(cn)) {
            return new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
        log.warn("Untranslated data-integrity violation (constraint={}); rethrowing original exception",
                cn != null ? cn : "<none>");
        if (e instanceof RuntimeException re) {
            return re;
        }
        // Wrapped without a message so no constraint name / SQL / class name leaks if
        // this
        // wrapper ever surfaces (it should not — the original is a RuntimeException in
        // practice).
        return new RuntimeException(e);
    }

    /**
     * Walks the entire cause chain and extracts the violated constraint name,
     * supporting both:
     * <ul>
     * <li>{@code org.hibernate.exception.ConstraintViolationException} —
     * {@code getConstraintName()}</li>
     * <li>{@code org.postgresql.util.PSQLException} —
     * {@code getServerErrorMessage().getConstraint()},
     * invoked reflectively because the JDBC driver is runtime-scoped (unavailable
     * at compile time)</li>
     * </ul>
     * Returns the first non-blank name found at any level, or {@code null}.
     * Package-private so the
     * PSQL fallback path can be exercised directly by tests.
     */
    static String extractConstraintName(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String name = constraintNameAt(t);
            if (name != null) {
                return name;
            }
            t = t.getCause();
        }
        return null;
    }

    /**
     * Extracts the constraint name from a single throwable (Hibernate wrapper or
     * raw PSQLException).
     */
    private static String constraintNameAt(Throwable t) {
        if (t instanceof org.hibernate.exception.ConstraintViolationException hce) {
            String name = hce.getConstraintName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return psqlConstraintName(t);
    }

    /**
     * Reflectively reads
     * {@code PSQLException#getServerErrorMessage().getConstraint()}. The PostgreSQL
     * JDBC driver is declared {@code runtime} in pom.xml, so its types cannot be
     * referenced at compile
     * time; matching by class name keeps this independent of the driver being on
     * the compile classpath.
     */
    private static String psqlConstraintName(Throwable t) {
        if (t == null || !"org.postgresql.util.PSQLException".equals(t.getClass().getName())) {
            return null;
        }
        try {
            java.lang.reflect.Method getServerMessage = t.getClass().getMethod("getServerErrorMessage");
            Object serverMessage = getServerMessage.invoke(t);
            if (serverMessage == null) {
                return null;
            }
            java.lang.reflect.Method getConstraint = serverMessage.getClass().getMethod("getConstraint");
            Object constraint = getConstraint.invoke(serverMessage);
            if (constraint instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (ReflectiveOperationException ignored) {
            // Driver API mismatch — leave to the caller (returns null → unknown).
        }
        return null;
    }

    private record QuestionRow(Long examQuestionId, String questionType, String content, BigDecimal defaultPoints) {
    }

    private record OptionRow(Long examQuestionId, String optionKey, String content, int position) {
    }
}
