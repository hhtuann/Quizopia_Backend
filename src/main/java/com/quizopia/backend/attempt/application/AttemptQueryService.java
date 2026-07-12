package com.quizopia.backend.attempt.application;

import com.quizopia.backend.academic.domain.model.StudentProfile;
import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.dto.AttemptDetailResponse;
import com.quizopia.backend.attempt.dto.AttemptDetailResponse.OptionView;
import com.quizopia.backend.attempt.dto.AttemptDetailResponse.QuestionView;
import com.quizopia.backend.attempt.dto.AttemptDetailResponse.SavedAnswerView;
import com.quizopia.backend.attempt.dto.MyAttemptsResponse;
import com.quizopia.backend.attempt.dto.MyAttemptsResponse.MyAttemptListItem;
import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Read-only query service for the attempt module: {@code GET /api/attempts/{id}} (detail) and
 * {@code GET /api/attempts/my} (history).
 *
 * <p><b>No mutation:</b> class-level {@code readOnly = true}; no pessimistic locks, no writes, no
 * lazy-submit, no session lazy-close. {@code serverTime} is a single {@link Instant#now(Clock)}
 * per request and is never persisted.
 * <p><b>Anti-enumeration:</b> detail returns {@code 404 ATTEMPT_NOT_FOUND} for a missing attempt AND
 * for an attempt owned by another student / cross-school — never 403 (no existence leak).
 * <p><b>No N+1:</b> detail = auth(3) + attempt(1) + joined questions(1) + options batch(1) + answers
 * batch(1) = 7 constant; my = auth(3) + page(1) + count(1) = 5 constant.
 * <p><b>Data-leak:</b> only student-safe fields; {@code answer_key} is projected to
 * (never the answer key JSON); options carry no {@code isCorrect}.
 */
@Service
@Transactional(readOnly = true)
public class AttemptQueryService {

    private static final Logger log = LoggerFactory.getLogger(AttemptQueryService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final AttemptAuthorizationService auth;
    private final AttemptRepository attemptRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttemptQueryService(AttemptAuthorizationService auth, AttemptRepository attemptRepo,
                               JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.auth = auth;
        this.attemptRepo = attemptRepo;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ============================================================
    // GET /api/attempts/{attemptId}
    // ============================================================

    public AttemptDetailResponse getAttemptDetail(Long userId, Long attemptId) {
        StudentProfile profile = auth.requireStudentWithPermissions(userId, "ATTEMPT_READ", "ATTEMPT_ANSWER_READ");
        Attempt attempt = loadOwnedAttempt(attemptId, profile); // 404 anti-enumeration
        Instant serverTime = Instant.now(clock);

        // 1 joined query: ordered attempt_questions + safe exam_question fields.
        List<AqRow> rows = jdbc.query(
                "SELECT aq.id AS aq_id, aq.exam_question_id, aq.question_type, aq.default_points, "
                        + "aq.display_order, aq.option_order, eq.content "
                        + "FROM attempt_questions aq JOIN exam_questions eq ON eq.id = aq.exam_question_id "
                        + "WHERE aq.attempt_id = ? ORDER BY aq.display_order ASC, aq.id ASC",
                (rs, n) -> new AqRow(
                        rs.getLong("aq_id"), rs.getLong("exam_question_id"), rs.getString("question_type"),
                        rs.getBigDecimal("default_points"), rs.getInt("display_order"),
                        parseJson(rs.getString("option_order")),
                        rs.getString("content")),
                attemptId);

        Map<Long, Map<String, String>> contentByEqAndKey = batchOptions(rows);       // 2 options batch
        Map<Long, AnswerRow> answerByAq = batchAnswers(attempt.getId());             // 3 answers batch

        int answeredCount = 0;
        List<QuestionView> questionViews = new ArrayList<>(rows.size());
        for (AqRow r : rows) {
            Map<String, String> contentByKey = contentByEqAndKey.getOrDefault(r.examQuestionId, Map.of());
            List<OptionView> options = renderOptions(r, contentByKey.keySet(), contentByKey);
            SavedAnswerView savedAnswer = null;
            AnswerRow ans = answerByAq.get(r.aqId);
            if (ans != null) {
                // Row exists even if payload is null (cleared answer) — keep savedAnswer so the client
                // sees the current sequence; only non-null payloads count toward answeredCount.
                savedAnswer = new SavedAnswerView(ans.payload, ans.sequence);
                if (ans.payload != null) {
                    answeredCount++;
                }
            }
            questionViews.add(new QuestionView(r.aqId, r.examQuestionId, r.questionType, r.displayOrder,
                    r.content, r.defaultPoints, options, savedAnswer));
        }
        return new AttemptDetailResponse(attempt.getId(), attempt.getExamSessionId(), attempt.getAttemptNumber(),
                attempt.getStatus().name(), attempt.getStartedAt(), attempt.getDeadlineAt(),
                attempt.getSubmittedAt(), serverTime, answeredCount, rows.size(), questionViews);
    }

    // ============================================================
    // GET /api/attempts/my
    // ============================================================

    public MyAttemptsResponse getMyAttempts(Long userId, int page, int size) {
        StudentProfile profile = auth.requireStudentWithPermission(userId, "ATTEMPT_READ");
        validatePaging(page, size);
        int safeSize = Math.min(size, MAX_PAGE_SIZE);
        long offset = (long) page * safeSize;

        List<MyAttemptListItem> items = jdbc.query(
                "SELECT a.id, a.exam_session_id, a.attempt_number, a.status, a.started_at, a.submitted_at, "
                        + "a.deadline_at, a.created_at, s.code AS session_code, s.title AS session_title, "
                        + "g.final_score, g.max_score "
                        + "FROM attempts a "
                        + "JOIN exam_sessions s ON s.id = a.exam_session_id "
                        + "LEFT JOIN grades g ON g.attempt_id = a.id "
                        + "WHERE a.student_profile_id = ? AND a.school_id = ? "
                        + "ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new MyAttemptListItem(
                        rs.getLong("id"), rs.getLong("exam_session_id"), rs.getString("session_code"),
                        rs.getString("session_title"), rs.getInt("attempt_number"), rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("submitted_at") != null ? rs.getTimestamp("submitted_at").toInstant() : null,
                        rs.getTimestamp("deadline_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBigDecimal("final_score"),
                        rs.getBigDecimal("max_score")),
                profile.getId(), profile.getSchoolId(), safeSize, offset);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM attempts WHERE student_profile_id = ? AND school_id = ?",
                Long.class, profile.getId(), profile.getSchoolId());

        long totalElements = total == null ? 0 : total;
        int totalPages = (int) Math.ceil((double) totalElements / safeSize);
        return new MyAttemptsResponse(items, page, safeSize, totalElements, totalPages, MyAttemptsResponse.SORT);
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Anti-enumeration: 404 for not-exist / foreign student / cross-school (no 403 to leak existence). */
    private Attempt loadOwnedAttempt(Long attemptId, StudentProfile profile) {
        return attemptRepo.findById(attemptId)
                .filter(a -> Objects.equals(a.getStudentProfileId(), profile.getId()))
                .filter(a -> Objects.equals(a.getSchoolId(), profile.getSchoolId()))
                .orElseThrow(() -> new AttemptException(AttemptErrorCode.ATTEMPT_NOT_FOUND));
    }

    /** Batch option content by (examQuestionId, optionKey) — selects option_key + content only. */
    private Map<Long, Map<String, String>> batchOptions(List<AqRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        StringJoiner placeholders = new StringJoiner(",", "(", ")");
        Long[] eqIds = new Long[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            placeholders.add("?");
            eqIds[i] = rows.get(i).examQuestionId;
        }
        Map<Long, Map<String, String>> result = new HashMap<>();
        jdbc.query("SELECT exam_question_id, option_key, content FROM exam_question_options "
                        + "WHERE exam_question_id IN " + placeholders,
                (RowCallbackHandler) rs -> {
                    result.computeIfAbsent(rs.getLong("exam_question_id"), k -> new HashMap<>())
                            .put(rs.getString("option_key"), rs.getString("content"));
                },
                (Object[]) eqIds);
        return result;
    }

    /** Batch answers for an attempt keyed by attempt_question_id (at most one per question). */
    private Map<Long, AnswerRow> batchAnswers(Long attemptId) {
        Map<Long, AnswerRow> map = new HashMap<>();
        jdbc.query("SELECT attempt_question_id, answer_payload, sequence_number "
                        + "FROM attempt_answers WHERE attempt_id = ?",
                (RowCallbackHandler) rs -> {
                    map.put(rs.getLong("attempt_question_id"),
                            new AnswerRow(parseJson(rs.getString("answer_payload")), rs.getLong("sequence_number")));
                },
                attemptId);
        return map;
    }

    /** Renders options from the persisted option_order with render-index positions (0..n-1). */
    private List<OptionView> renderOptions(AqRow r, java.util.Set<String> sourceKeys, Map<String, String> contentByKey) {
        List<String> orderedKeys = AttemptService.validatedOrderedOptionKeys(
                r.questionType, r.aqId, r.optionOrder, sourceKeys);
        List<OptionView> options = new ArrayList<>(orderedKeys.size());
        int renderIndex = 0;
        for (String key : orderedKeys) {
            options.add(new OptionView(key, contentByKey.get(key), renderIndex++));
        }
        return options;
    }

    private JsonNode parseJson(String text) {
        if (text == null) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (RuntimeException e) {
            // Corrupt JSONB snapshot (DB CHECKs normally prevent this) → 400, never a 500 leak.
            log.warn("corrupt JSONB snapshot; treating as validation error: {}", e.toString());
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
    }

    private static void validatePaging(int page, int size) {
        if (page < 0 || size < 1) {
            throw new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
        }
    }

    private record AqRow(Long aqId, Long examQuestionId, String questionType, BigDecimal defaultPoints,
                         Integer displayOrder, JsonNode optionOrder, String content) {}

    private record AnswerRow(JsonNode payload, Long sequence) {}
}
