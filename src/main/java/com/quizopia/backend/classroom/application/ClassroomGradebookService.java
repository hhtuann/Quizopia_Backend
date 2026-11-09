package com.quizopia.backend.classroom.application;

import com.quizopia.backend.classroom.domain.model.Classroom;
import com.quizopia.backend.question.dto.PageResponse;
import com.quizopia.backend.classroom.dto.reporting.ClassroomAnalyticsResponse;
import com.quizopia.backend.classroom.dto.reporting.GradebookCell;
import com.quizopia.backend.classroom.dto.reporting.GradebookResponse;
import com.quizopia.backend.classroom.dto.reporting.GradebookSessionColumn;
import com.quizopia.backend.classroom.dto.reporting.GradebookStudentRow;
import com.quizopia.backend.classroom.dto.reporting.LearnerMyResultsItem;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.classroom.repository.ClassroomMemberRepository;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CLASS-M3 (issue #54): class-scoped Gradebook / Analytics / learner
 * my-results — a reporting LAYER over the existing attempt/grade data, never
 * a second grading engine.
 *
 * <h2>Cohort model (design gate, docs/class-m3-attribution-design.md)</h2>
 * Membership epochs come from {@code classroom_membership_periods} (V30,
 * trigger-maintained). For a session whose taking window has begun, the class
 * cohort is the users whose membership interval {@code [joined_at, left_at)}
 * overlaps the window {@code [max(startsAt, openedAt), min(endsAt, closedAt)]};
 * the window is EMPTY (NOT_HELD) when the session expired without ever
 * opening (lazy-expire fabricates opened_at = closed_at &gt; ends_at, so the
 * overlap test itself degenerates). Upcoming sessions show the CURRENT roster
 * prospectively and never contribute to completed-analytics denominators.
 * Assignment attribution relies on the M2 window-start freeze (all three
 * mutation paths reject SCHEDULED sessions past startsAt) — the current
 * {@code exam_session_classes} row IS the assignment record for any window
 * that began after that guarantee.
 *
 * <h2>Metric denominators (issue #54 gate 3)</h2>
 * cohort = DISTINCT cohort users; started = cohort users with ≥1 attempt;
 * submitted = ≥1 SUBMITTED/GRADED attempt; graded = ≥1 persisted Grade;
 * completion% = submitted/cohort (NULL when cohort = 0); average% = mean of
 * BEST grade percentages among graded cohort users. BEST selection reuses the
 * {@code SessionResultService} ordering VERBATIM (percentage DESC NULLS LAST,
 * final_score DESC, submitted_at ASC, attempt_id ASC — identical to
 * {@code BestResultComparator}); no re-grading, missing results are NULL,
 * never zero.
 *
 * <h2>Authorization</h2>
 * Teacher routes: {@code requireOwnedClassroomForReporting} (CLASSROOM_READ +
 * owner). Learner route: current membership, uniform 404 (M2 no-oracle rule).
 * Reads refresh session lifecycle for the classroom OWNER first (M2 pattern).
 */
@Service
public class ClassroomGradebookService {

    /** See docs/class-m3-attribution-design.md §4 — honest coverage marker. */
    public static final String HISTORY_COVERAGE = "SINCE_MIGRATION";

    private static final int MAX_SESSION_COLUMNS = 10;
    private static final int DEFAULT_SESSION_COLUMNS = 5;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_COHORT_USERS = 5000;
    /** Export row bound — twin of the cohort cap, package-private for the exporter. */
    static final int MAX_EXPORT_ROWS = 5000;
    private static final int TREND_LIMIT = 20;
    private static final Set<String> TEACHER_STATUS_FILTER = Set.of(
            "DRAFT", "SCHEDULED", "OPEN", "CLOSED", "CANCELLED");
    private static final Set<String> SORT_KEYS = Set.of("displayName", "meanPercentage", "completion");
    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private final ClassroomService classroomService;
    private final ClassroomRepository classroomRepository;
    private final ClassroomMemberRepository memberRepository;
    private final ExamSessionRepository sessionRepository;
    private final NamedParameterJdbcTemplate jdbc;

    public ClassroomGradebookService(ClassroomService classroomService,
            ClassroomRepository classroomRepository,
            ClassroomMemberRepository memberRepository,
            ExamSessionRepository sessionRepository,
            NamedParameterJdbcTemplate jdbc) {
        this.classroomService = classroomService;
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.sessionRepository = sessionRepository;
        this.jdbc = jdbc;
    }

    // ============================================================
    // Internal session/window model
    // ============================================================

    /** Evaluated taking window of one session. */
    record SessionWindow(Long sessionId, String state, Instant start, Instant end) {
        static final String UPCOMING = "UPCOMING";
        static final String WINDOW = "WINDOW";
        static final String NOT_HELD = "NOT_HELD";
    }

    record SessionMeta(Long id, String code, String title, String status,
            Instant startsAt, Instant endsAt, Instant openedAt, Instant closedAt,
            Long examId, String examTitle) {
    }

    /** Attempts/grades state of one (session, user). */
    record AttemptState(long attempts, long submitted) {
    }

    /** Best graded result of one (session, user) — SCORED cell payload. */
    record BestResult(long attemptId, BigDecimal score, BigDecimal maxScore,
            BigDecimal percentage, String gradeStatus) {
    }

    private SessionWindow windowOf(SessionMeta s, Instant now) {
        switch (s.status()) {
            case "DRAFT" -> {
                return new SessionWindow(s.id(), "DRAFT", null, null);
            }
            case "CANCELLED" -> {
                return new SessionWindow(s.id(), "CANCELLED", null, null);
            }
            case "SCHEDULED" -> {
                if (now.isBefore(s.startsAt())) {
                    return new SessionWindow(s.id(), SessionWindow.UPCOMING, null, null);
                }
                // Due but not yet lazy-opened: the window is the schedule.
                return new SessionWindow(s.id(), SessionWindow.WINDOW, s.startsAt(), s.endsAt());
            }
            case "OPEN", "CLOSED" -> {
                Instant start = maxNullable(s.startsAt(), s.openedAt());
                Instant rawEnd = s.closedAt() != null && s.closedAt().isBefore(s.endsAt())
                        ? s.closedAt() : s.endsAt();
                // Never actually opened (lazy-expire fabricates opened_at =
                // closed_at > ends_at) => empty interval => NOT_HELD.
                if (start == null || !rawEnd.isAfter(start)) {
                    return new SessionWindow(s.id(), SessionWindow.NOT_HELD, null, null);
                }
                return new SessionWindow(s.id(), SessionWindow.WINDOW, start, rawEnd);
            }
            default -> {
                return new SessionWindow(s.id(), SessionWindow.NOT_HELD, null, null);
            }
        }
    }

    private static Instant maxNullable(Instant a, Instant b) {
        if (b == null) return a;
        if (a == null) return b;
        return a.isAfter(b) ? a : b;
    }

    private List<SessionMeta> assignedSessions(Long classroomId, Long ownerId, String status, String search,
            Integer limit) {
        return assignedSessions(classroomId, ownerId, status, search, limit, null);
    }

    private List<SessionMeta> assignedSessions(Long classroomId, Long ownerId, String status, String search,
            Integer limit, Long sessionId) {
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.code, s.title, s.status, s.starts_at, s.ends_at, "
                        + "s.opened_at, s.closed_at, s.exam_id, e.title AS exam_title "
                        + "FROM exam_session_classes esc "
                        + "JOIN exam_sessions s ON s.id = esc.exam_session_id "
                        + "JOIN exams e ON e.id = s.exam_id "
                        + "WHERE esc.classroom_id = :cid AND s.created_by = :uid");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cid", classroomId).addValue("uid", ownerId);
        if (sessionId != null) {
            sql.append(" AND s.id = :sid");
            params.addValue("sid", sessionId);
        }
        if (status != null && !status.isBlank()) {
            if (!TEACHER_STATUS_FILTER.contains(status)) {
                throw new ClassroomException(ClassroomErrorCode.CLASSROOM_GRADEBOOK_INVALID_QUERY);
            }
            sql.append(" AND s.status = :status");
            params.addValue("status", status);
        }
        if (search != null && !search.isBlank()) {
            String escaped = likeEscape(search.trim().toLowerCase());
            sql.append(" AND (LOWER(s.title) LIKE :q ESCAPE '\\' OR LOWER(s.code) LIKE :q ESCAPE '\\' "
                    + "OR LOWER(e.title) LIKE :q ESCAPE '\\')");
            params.addValue("q", "%" + escaped + "%");
        }
        sql.append(" ORDER BY s.starts_at DESC, s.id DESC");
        if (limit != null) {
            sql.append(" LIMIT :limit");
            params.addValue("limit", limit);
        }
        return jdbc.query(sql.toString(), params, (rs, n) -> new SessionMeta(
                rs.getLong("id"), rs.getString("code"), rs.getString("title"), rs.getString("status"),
                rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                toInstant(rs.getTimestamp("opened_at")), toInstant(rs.getTimestamp("closed_at")),
                rs.getLong("exam_id"), rs.getString("exam_title")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    /** LIKE-escapes a lowercase search token (M2 convention). */
    private static String likeEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ============================================================
    // Cohort / attempt queries (shared by every read)
    // ============================================================

    /**
     * Cohort pairs (sessionId, userId) for the given sessions: period overlap
     * for windowed sessions, current roster for upcoming ones. Upcoming
     * sessions with zero current members simply contribute no pairs.
     */
    private Map<Long, Set<Long>> cohortPairs(Long classroomId, Map<Long, SessionWindow> windows) {
        Map<Long, Set<Long>> pairs = new HashMap<>();
        List<Long> windowed = new ArrayList<>();
        List<Long> upcoming = new ArrayList<>();
        for (SessionWindow w : windows.values()) {
            if (SessionWindow.WINDOW.equals(w.state())) {
                windowed.add(w.sessionId());
            } else if (SessionWindow.UPCOMING.equals(w.state())) {
                upcoming.add(w.sessionId());
            }
        }
        if (!windowed.isEmpty()) {
            // VALUES-join lets every session carry its own window in one round trip.
            StringBuilder values = new StringBuilder();
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("cid", classroomId);
            for (int i = 0; i < windowed.size(); i++) {
                if (i > 0) values.append(", ");
                values.append("(:sid").append(i).append(", CAST(:ws").append(i)
                        .append(" AS timestamptz), CAST(:we").append(i).append(" AS timestamptz))");
                params.addValue("sid" + i, windowed.get(i));
                params.addValue("ws" + i, Timestamp.from(windows.get(windowed.get(i)).start()));
                params.addValue("we" + i, Timestamp.from(windows.get(windowed.get(i)).end()));
            }
            String sql = "SELECT w.sid AS sid, p.user_id AS uid FROM (VALUES "
                    + values + ") AS w(sid, ws, we) "
                    + "JOIN classroom_membership_periods p ON p.classroom_id = :cid "
                    + "AND tstzrange(p.joined_at, COALESCE(p.left_at, 'infinity'), '[)') "
                    + "&& tstzrange(w.ws, w.we, '[)')";
            jdbc.query(sql, params, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                pairs.computeIfAbsent(rs.getLong("sid"), k -> new HashSet<>()).add(rs.getLong("uid"));
            });
        }
        if (!upcoming.isEmpty()) {
            String sql = "SELECT s.id AS sid, cm.user_id AS uid FROM exam_sessions s "
                    + "JOIN classroom_members cm ON cm.classroom_id = :cid WHERE s.id IN (:sids)";
            jdbc.query(sql, new MapSqlParameterSource().addValue("cid", classroomId)
                            .addValue("sids", upcoming),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> pairs
                            .computeIfAbsent(rs.getLong("sid"), k -> new HashSet<>())
                            .add(rs.getLong("uid")));
        }
        return pairs;
    }

    /** Attempt counters per (session, user) restricted to the cohort users. */
    private Map<Long, Map<Long, AttemptState>> attemptStates(List<Long> sessionIds, Set<Long> userIds) {
        Map<Long, Map<Long, AttemptState>> states = new HashMap<>();
        if (sessionIds.isEmpty() || userIds.isEmpty()) {
            return states;
        }
        String sql = "SELECT a.exam_session_id AS sid, a.user_id AS uid, "
                + "COUNT(*) AS attempts, "
                + "COUNT(*) FILTER (WHERE a.status IN ('SUBMITTED','GRADED')) AS submitted "
                + "FROM attempts a "
                + "WHERE a.exam_session_id IN (:sids) AND a.user_id IN (:uids) "
                + "GROUP BY a.exam_session_id, a.user_id";
        // Chunked IN-lists (same convention as currentMembers/displayNames).
        for (List<Long> sidChunk : chunked(sessionIds)) {
            for (List<Long> uidChunk : chunked(userIds)) {
                jdbc.query(sql, new MapSqlParameterSource().addValue("sids", sidChunk)
                                .addValue("uids", uidChunk),
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> states
                                .computeIfAbsent(rs.getLong("sid"), k -> new HashMap<>())
                                .put(rs.getLong("uid"), new AttemptState(
                                        rs.getLong("attempts"), rs.getLong("submitted"))));
            }
        }
        return states;
    }

    private static <T> List<List<T>> chunked(java.util.Collection<T> items) {
        List<List<T>> chunks = new ArrayList<>();
        List<T> all = new ArrayList<>(items);
        for (int i = 0; i < all.size(); i += 500) {
            chunks.add(all.subList(i, Math.min(i + 500, all.size())));
        }
        return chunks;
    }

    /**
     * Best GRADED result per (session, user) using the SessionResultService /
     * BestResultComparator ordering VERBATIM: percentage DESC NULLS LAST,
     * final_score DESC, submitted_at ASC, attempt_id ASC.
     */
    private Map<Long, Map<Long, BestResult>> bestResults(List<Long> sessionIds, Set<Long> userIds) {
        Map<Long, Map<Long, BestResult>> best = new HashMap<>();
        if (sessionIds.isEmpty() || userIds.isEmpty()) {
            return best;
        }
        String sql = "SELECT sid, uid, attempt_id, final_score, max_score, percentage, grade_status FROM ( "
                + "  SELECT a.exam_session_id AS sid, a.user_id AS uid, a.id AS attempt_id, "
                + "         a.submitted_at, g.final_score, g.max_score, g.percentage, g.status AS grade_status, "
                + "         ROW_NUMBER() OVER (PARTITION BY a.exam_session_id, a.user_id "
                + "             ORDER BY g.percentage DESC NULLS LAST, g.final_score DESC, "
                + "                      a.submitted_at ASC, a.id ASC) AS rn "
                + "  FROM attempts a JOIN grades g ON g.attempt_id = a.id "
                + "  WHERE a.exam_session_id IN (:sids) AND a.user_id IN (:uids) "
                + "    AND a.status IN ('SUBMITTED','GRADED') "
                + ") t WHERE rn = 1";
        for (List<Long> sidChunk : chunked(sessionIds)) {
            for (List<Long> uidChunk : chunked(userIds)) {
                jdbc.query(sql, new MapSqlParameterSource().addValue("sids", sidChunk)
                                .addValue("uids", uidChunk),
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> best
                                .computeIfAbsent(rs.getLong("sid"), k -> new HashMap<>())
                                .put(rs.getLong("uid"), new BestResult(
                                        rs.getLong("attempt_id"), rs.getBigDecimal("final_score"),
                                        rs.getBigDecimal("max_score"), rs.getBigDecimal("percentage"),
                                        rs.getString("grade_status"))));
            }
        }
        return best;
    }

    /** Cell derivation — the single source of truth for every consumer. */
    private GradebookCell cellOf(SessionWindow window, boolean inCohort, boolean currentMember,
            AttemptState state, BestResult best) {
        return switch (window.state()) {
            case "DRAFT" -> GradebookCell.state("DRAFT");
            case "CANCELLED" -> GradebookCell.state("CANCELLED");
            case SessionWindow.NOT_HELD -> GradebookCell.state("NOT_HELD");
            case SessionWindow.UPCOMING -> currentMember
                    ? GradebookCell.state("UPCOMING") : GradebookCell.state("NOT_IN_COHORT");
            default -> {
                if (!inCohort) {
                    yield GradebookCell.state("NOT_IN_COHORT");
                }
                if (state == null) {
                    yield GradebookCell.state("NOT_ATTEMPTED");
                }
                if (best != null) {
                    yield new GradebookCell("SCORED", best.score(), best.maxScore(), best.percentage(),
                            best.attemptId(), state.attempts(), best.gradeStatus());
                }
                if (state.submitted() > 0) {
                    yield new GradebookCell("PENDING_GRADING", null, null, null, null,
                            state.attempts(), null);
                }
                yield new GradebookCell("IN_PROGRESS", null, null, null, null, state.attempts(), null);
            }
        };
    }

    // ============================================================
    // GET /api/classrooms/{id}/gradebook/sessions — session columns
    // ============================================================

    @Transactional
    public PageResponse<GradebookSessionColumn> listGradebookSessions(Long userId, Long classroomId,
            int page, int size, String status, String search) {
        Classroom classroom = classroomService.requireOwnedClassroomForReporting(userId, classroomId);
        sessionRepository.refreshLifecycleForCreator(classroom.getOwnerUserId(), Instant.now());

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // Count shares the filter shape.
        StringBuilder where = new StringBuilder(
                " FROM exam_session_classes esc JOIN exam_sessions s ON s.id = esc.exam_session_id "
                        + "JOIN exams e ON e.id = s.exam_id "
                        + "WHERE esc.classroom_id = :cid AND s.created_by = :uid");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cid", classroomId).addValue("uid", userId);
        if (status != null && !status.isBlank()) {
            if (!TEACHER_STATUS_FILTER.contains(status)) {
                throw new ClassroomException(ClassroomErrorCode.CLASSROOM_GRADEBOOK_INVALID_QUERY);
            }
            where.append(" AND s.status = :status");
            params.addValue("status", status);
        }
        if (search != null && !search.isBlank()) {
            String escaped = likeEscape(search.trim().toLowerCase());
            where.append(" AND (LOWER(s.title) LIKE :q ESCAPE '\\' OR LOWER(s.code) LIKE :q ESCAPE '\\' "
                    + "OR LOWER(e.title) LIKE :q ESCAPE '\\')");
            params.addValue("q", "%" + escaped + "%");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, params, Long.class);
        long totalCount = total != null ? total : 0L;
        int totalPages = (int) ((totalCount + safeSize - 1) / safeSize);
        int safePage = Math.max(page, 0);
        if (totalCount > 0 && safePage >= totalPages) {
            safePage = totalPages - 1; // clamp-and-relabel (M2 SQL contract)
        }

        List<SessionMeta> metas = jdbc.query(
                "SELECT s.id, s.code, s.title, s.status, s.starts_at, s.ends_at, s.opened_at, s.closed_at, "
                        + "s.exam_id, e.title AS exam_title" + where
                        + " ORDER BY s.starts_at DESC, s.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", safeSize).addValue("offset", safePage * safeSize),
                (rs, n) -> new SessionMeta(
                        rs.getLong("id"), rs.getString("code"), rs.getString("title"), rs.getString("status"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                        toInstant(rs.getTimestamp("opened_at")), toInstant(rs.getTimestamp("closed_at")),
                        rs.getLong("exam_id"), rs.getString("exam_title")));

        Instant now = Instant.now();
        Map<Long, SessionWindow> windows = new LinkedHashMap<>();
        for (SessionMeta m : metas) {
            windows.put(m.id(), windowOf(m, now));
        }
        Map<Long, Set<Long>> pairs = cohortPairs(classroomId, windows);
        Set<Long> allUsers = new HashSet<>();
        pairs.values().forEach(allUsers::addAll);
        Map<Long, Map<Long, AttemptState>> states =
                attemptStates(new ArrayList<>(windows.keySet()), allUsers);
        Map<Long, Map<Long, BestResult>> best = bestResults(new ArrayList<>(windows.keySet()), allUsers);

        List<GradebookSessionColumn> items = new ArrayList<>();
        for (SessionMeta m : metas) {
            SessionWindow w = windows.get(m.id());
            Set<Long> cohort = pairs.getOrDefault(m.id(), Set.of());
            long started = 0L;
            long submitted = 0L;
            long graded = 0L;
            List<BigDecimal> pcts = new ArrayList<>();
            for (Long uid : cohort) {
                AttemptState st = states.getOrDefault(m.id(), Map.of()).get(uid);
                BestResult br = best.getOrDefault(m.id(), Map.of()).get(uid);
                if (st != null) {
                    started++;
                    if (st.submitted() > 0) {
                        submitted++;
                    }
                }
                if (br != null) {
                    graded++;
                    if (br.percentage() != null) {
                        pcts.add(br.percentage());
                    }
                }
            }
            boolean windowed = SessionWindow.WINDOW.equals(w.state());
            items.add(new GradebookSessionColumn(m.id(), m.code(), m.title(), m.status(),
                    m.startsAt(), m.endsAt(), w.state(), m.examId(), m.examTitle(),
                    cohort.size(), started, submitted, graded,
                    windowed ? ratio(submitted, cohort.size()) : null, mean(pcts)));
        }
        return new PageResponse<>(items, safePage, safeSize, totalCount, totalPages, "startsAt,desc");
    }

    // ============================================================
    // GET /api/classrooms/{id}/gradebook — the matrix
    // ============================================================

    @Transactional
    public GradebookResponse gradebook(Long userId, Long classroomId, List<Long> sessionIds,
            int page, int size, String search, String sort, String direction) {
        return computeGradebook(userId, classroomId, sessionIds, page, size, search, sort,
                direction, MAX_PAGE_SIZE);
    }

    /** Same computation with a raised row cap — export only (same package). */
    @Transactional
    GradebookResponse gradebookForExport(Long userId, Long classroomId, List<Long> sessionIds,
            String search) {
        return computeGradebook(userId, classroomId, sessionIds, 0, MAX_EXPORT_ROWS, search,
                "displayName", "asc", MAX_EXPORT_ROWS);
    }

    private GradebookResponse computeGradebook(Long userId, Long classroomId, List<Long> sessionIds,
            int page, int size, String search, String sort, String direction, int sizeCap) {
        Classroom classroom = classroomService.requireOwnedClassroomForReporting(userId, classroomId);
        sessionRepository.refreshLifecycleForCreator(classroom.getOwnerUserId(), Instant.now());

        String safeSort = sort == null || sort.isBlank() ? "displayName" : sort;
        String safeDir = direction == null || direction.isBlank() ? "asc"
                : direction.toLowerCase();
        if (!SORT_KEYS.contains(safeSort) || !DIRECTIONS.contains(safeDir)) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_GRADEBOOK_INVALID_QUERY);
        }
        int safeSize = Math.min(Math.max(size, 1), sizeCap);

        // Column selection: explicit bounded sessionIds, or the default slice.
        List<SessionMeta> metas = selectSessions(classroomId, userId, sessionIds);
        Instant now = Instant.now();
        Map<Long, SessionWindow> windows = new LinkedHashMap<>();
        for (SessionMeta m : metas) {
            windows.put(m.id(), windowOf(m, now));
        }
        Map<Long, Set<Long>> pairs = cohortPairs(classroomId, windows);
        Set<Long> allUsers = new HashSet<>();
        pairs.values().forEach(allUsers::addAll);
        if (allUsers.size() > MAX_COHORT_USERS) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_GRADEBOOK_TOO_LARGE);
        }
        Map<Long, Map<Long, AttemptState>> states =
                attemptStates(new ArrayList<>(windows.keySet()), allUsers);
        Map<Long, Map<Long, BestResult>> best = bestResults(new ArrayList<>(windows.keySet()), allUsers);
        Set<Long> currentMembers = currentMembers(classroomId, allUsers);

        // Row population + per-student aggregates.
        Map<Long, String> names = displayNames(allUsers);
        String rawToken = search != null && !search.isBlank()
                ? search.trim().toLowerCase() : null;
        List<GradebookStudentRow> rows = new ArrayList<>();
        for (Long uid : allUsers) {
            String name = names.getOrDefault(uid, "User " + uid);
            if (rawToken != null && !name.toLowerCase().contains(rawToken)) {
                continue;
            }
            rows.add(buildRow(uid, name, currentMembers.contains(uid), metas, windows, pairs,
                    states, best));
        }
        rows.sort(rowComparator(safeSort, safeDir));
        long totalElements = rows.size();
        int totalPages = (int) ((totalElements + safeSize - 1) / safeSize);
        int safePage = Math.max(page, 0);
        if (totalElements > 0 && safePage >= totalPages) {
            safePage = totalPages - 1;
        }
        List<GradebookStudentRow> pageRows = rows.subList(
                Math.toIntExact(safePage * (long) safeSize),
                Math.toIntExact(Math.min((safePage + 1L) * safeSize, totalElements)));

        // Column summaries reuse the exact same cohort/attempt data.
        List<GradebookSessionColumn> columns = new ArrayList<>();
        for (SessionMeta m : metas) {
            SessionWindow w = windows.get(m.id());
            Set<Long> cohort = pairs.getOrDefault(m.id(), Set.of());
            long started = 0L;
            long submitted = 0L;
            long graded = 0L;
            List<BigDecimal> pcts = new ArrayList<>();
            for (Long uid : cohort) {
                AttemptState st = states.getOrDefault(m.id(), Map.of()).get(uid);
                BestResult br = best.getOrDefault(m.id(), Map.of()).get(uid);
                if (st != null) {
                    started++;
                    if (st.submitted() > 0) {
                        submitted++;
                    }
                }
                if (br != null) {
                    graded++;
                    if (br.percentage() != null) {
                        pcts.add(br.percentage());
                    }
                }
            }
            boolean columnWindowed = SessionWindow.WINDOW.equals(w.state());
            columns.add(new GradebookSessionColumn(m.id(), m.code(), m.title(), m.status(),
                    m.startsAt(), m.endsAt(), w.state(), m.examId(), m.examTitle(),
                    cohort.size(), started, submitted, graded,
                    columnWindowed ? ratio(submitted, cohort.size()) : null, mean(pcts)));
        }
        return new GradebookResponse(columns, pageRows, safePage, safeSize, totalElements, totalPages,
                safeSort + "," + safeDir, HISTORY_COVERAGE);
    }

    private List<SessionMeta> selectSessions(Long classroomId, Long ownerId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return assignedSessions(classroomId, ownerId, null, null, DEFAULT_SESSION_COLUMNS);
        }
        if (sessionIds.size() > MAX_SESSION_COLUMNS) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_GRADEBOOK_INVALID_QUERY);
        }
        List<SessionMeta> all = assignedSessions(classroomId, ownerId, null, null, null);
        Map<Long, SessionMeta> byId = new HashMap<>();
        for (SessionMeta m : all) {
            byId.put(m.id(), m);
        }
        List<SessionMeta> selected = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long sid : sessionIds) {
            if (!seen.add(sid)) {
                continue; // tolerate duplicates
            }
            SessionMeta m = byId.get(sid);
            if (m == null) {
                // Unknown or not assigned to THIS classroom — reject the query.
                throw new ClassroomException(ClassroomErrorCode.CLASSROOM_GRADEBOOK_INVALID_QUERY);
            }
            selected.add(m);
        }
        return selected;
    }

    private GradebookStudentRow buildRow(Long uid, String name, boolean currentMember,
            List<SessionMeta> metas, Map<Long, SessionWindow> windows, Map<Long, Set<Long>> pairs,
            Map<Long, Map<Long, AttemptState>> states, Map<Long, Map<Long, BestResult>> best) {
        List<GradebookCell> cells = new ArrayList<>();
        List<BigDecimal> gradedPcts = new ArrayList<>();
        long cohortSessions = 0L;
        long submittedSessions = 0L;
        for (SessionMeta m : metas) {
            SessionWindow w = windows.get(m.id());
            boolean inCohort = pairs.getOrDefault(m.id(), Set.of()).contains(uid);
            AttemptState st = states.getOrDefault(m.id(), Map.of()).get(uid);
            BestResult br = best.getOrDefault(m.id(), Map.of()).get(uid);
            GradebookCell cell = cellOf(w, inCohort, currentMember, st, br);
            cells.add(cell);
            if (SessionWindow.WINDOW.equals(w.state()) && inCohort) {
                cohortSessions++;
                if (st != null && st.submitted() > 0) {
                    submittedSessions++;
                }
                if ("SCORED".equals(cell.status()) && cell.percentage() != null) {
                    gradedPcts.add(cell.percentage());
                }
            }
        }
        return new GradebookStudentRow(uid, name, currentMember, cells, mean(gradedPcts),
                cohortSessions, submittedSessions, ratio(submittedSessions, cohortSessions));
    }

    private static Comparator<GradebookStudentRow> rowComparator(String sort, String direction) {
        boolean desc = "desc".equals(direction);
        Comparator<BigDecimal> valueOrder = desc
                ? Comparator.reverseOrder() : Comparator.naturalOrder();
        Comparator<GradebookStudentRow> key;
        switch (sort) {
            case "meanPercentage" -> key = Comparator.comparing(GradebookStudentRow::meanPercentage,
                    Comparator.nullsLast(valueOrder));
            case "completion" -> key = Comparator.comparing(GradebookStudentRow::completionPercentage,
                    Comparator.nullsLast(valueOrder));
            default -> {
                Comparator<String> nameOrder;
                if (desc) {
                    nameOrder = Comparator.<String>naturalOrder().reversed();
                } else {
                    nameOrder = Comparator.naturalOrder();
                }
                key = Comparator.comparing(GradebookStudentRow::displayName,
                        Comparator.nullsLast(nameOrder));
            }
        }
        // Nulls stay LAST in both directions; the userId tie-break stays ASC.
        return key.thenComparing(GradebookStudentRow::userId);
    }

    private Set<Long> currentMembers(Long classroomId, Set<Long> amongUsers) {
        Set<Long> current = new HashSet<>();
        if (amongUsers.isEmpty()) {
            return current;
        }
        // Chunked to keep the IN-list bounded.
        List<Long> users = new ArrayList<>(amongUsers);
        for (int i = 0; i < users.size(); i += 500) {
            List<Long> chunk = users.subList(i, Math.min(i + 500, users.size()));
            jdbc.query("SELECT user_id FROM classroom_members WHERE classroom_id = :cid AND user_id IN (:uids)",
                    new MapSqlParameterSource().addValue("cid", classroomId).addValue("uids", chunk),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> current.add(rs.getLong(1)));
        }
        return current;
    }

    private Map<Long, String> displayNames(Set<Long> users) {
        Map<Long, String> names = new HashMap<>();
        List<Long> list = new ArrayList<>(users);
        for (int i = 0; i < list.size(); i += 500) {
            List<Long> chunk = list.subList(i, Math.min(i + 500, list.size()));
            jdbc.query("SELECT id, display_name FROM users WHERE id IN (:uids)",
                    new MapSqlParameterSource().addValue("uids", chunk),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> names.put(rs.getLong(1), rs.getString(2)));
        }
        return names;
    }

    // ============================================================
    // GET /api/classrooms/{id}/analytics
    // ============================================================

    @Transactional
    public ClassroomAnalyticsResponse analytics(Long userId, Long classroomId, Long sessionId) {
        Classroom classroom = classroomService.requireOwnedClassroomForReporting(userId, classroomId);
        sessionRepository.refreshLifecycleForCreator(classroom.getOwnerUserId(), Instant.now());
        Instant now = Instant.now();

        long rosterSize = memberRepository.countByClassroomId(classroomId);
        Long assignedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_session_classes esc JOIN exam_sessions s "
                        + "ON s.id = esc.exam_session_id WHERE esc.classroom_id = :cid AND s.created_by = :uid",
                new MapSqlParameterSource().addValue("cid", classroomId).addValue("uid", userId),
                Long.class);

        if (sessionId != null) {
            return sessionAnalytics(classroomId, userId, sessionId, rosterSize,
                    assignedCount != null ? assignedCount : 0L, now);
        }

        // Overview: bounded trend (latest TREND_LIMIT, chronological) for
        // display; the overall KPI is a separate SQL aggregation over ALL
        // eligible WINDOW sessions (Final Gate P2-02 — the presentation
        // bound must never sample the denominator).
        List<SessionMeta> recent = assignedSessions(classroomId, userId, null, null, TREND_LIMIT);
        Map<Long, SessionWindow> windows = new LinkedHashMap<>();
        for (SessionMeta m : recent) {
            windows.put(m.id(), windowOf(m, now));
        }
        Map<Long, Set<Long>> pairs = cohortPairs(classroomId, windows);
        Set<Long> allUsers = new HashSet<>();
        pairs.values().forEach(allUsers::addAll);
        Map<Long, Map<Long, AttemptState>> states =
                attemptStates(new ArrayList<>(windows.keySet()), allUsers);
        Map<Long, Map<Long, BestResult>> best = bestResults(new ArrayList<>(windows.keySet()), allUsers);

        List<ClassroomAnalyticsResponse.SessionAnalyticsTrend> trends = new ArrayList<>();
        List<SessionMeta> chronological = new ArrayList<>(recent);
        chronological.sort(Comparator.comparing(SessionMeta::startsAt).thenComparing(SessionMeta::id));
        for (SessionMeta m : chronological) {
            Set<Long> cohort = pairs.getOrDefault(m.id(), Set.of());
            long started = 0L;
            long submitted = 0L;
            long graded = 0L;
            List<BigDecimal> pcts = new ArrayList<>();
            for (Long uid : cohort) {
                AttemptState st = states.getOrDefault(m.id(), Map.of()).get(uid);
                BestResult br = best.getOrDefault(m.id(), Map.of()).get(uid);
                if (st != null) {
                    started++;
                    if (st.submitted() > 0) {
                        submitted++;
                    }
                }
                if (br != null) {
                    graded++;
                    if (br.percentage() != null) {
                        pcts.add(br.percentage());
                    }
                }
            }
            boolean windowed = SessionWindow.WINDOW.equals(windows.get(m.id()).state());
            trends.add(new ClassroomAnalyticsResponse.SessionAnalyticsTrend(m.id(), m.code(), m.title(),
                    m.status(), windows.get(m.id()).state(), m.startsAt(), cohort.size(), started,
                    submitted, graded,
                    windowed ? ratio(submitted, cohort.size()) : null, mean(pcts)));
        }

        // Overall completion: SQL-level aggregation of DISTINCT
        // (session, learner) cohort pairs over EVERY eligible window of the
        // class. Window shape mirrors windowOf: OPEN/CLOSED only (the owner
        // lifecycle refresh above has already moved every due SCHEDULED
        // session), [max(starts, opened), min(ends, closed)], and the
        // never-held fabrication degenerates ws >= we and drops out.
        String overallSql =
                "WITH windows AS ( "
                        + "  SELECT s.id, "
                        + "         GREATEST(s.starts_at, COALESCE(s.opened_at, s.starts_at)) AS ws, "
                        + "         LEAST(s.ends_at, COALESCE(s.closed_at, s.ends_at)) AS we "
                        + "  FROM exam_session_classes esc "
                        + "  JOIN exam_sessions s ON s.id = esc.exam_session_id "
                        + "  WHERE esc.classroom_id = :cid AND s.created_by = :uid "
                        + "    AND s.status IN ('OPEN','CLOSED') "
                        + "), eligible AS ( "
                        + "  SELECT * FROM windows WHERE ws < we "
                        + ") "
                        + "SELECT COUNT(DISTINCT (w.id, p.user_id)) AS eligible_pairs, "
                        + "       COUNT(DISTINCT (w.id, p.user_id)) "
                        + "         FILTER (WHERE EXISTS (SELECT 1 FROM attempts a "
                        + "              WHERE a.exam_session_id = w.id AND a.user_id = p.user_id "
                        + "                AND a.status IN ('SUBMITTED','GRADED'))) AS completed_pairs "
                        + "FROM eligible w "
                        + "JOIN classroom_membership_periods p ON p.classroom_id = :cid "
                        + "  AND tstzrange(p.joined_at, COALESCE(p.left_at, 'infinity'), '[)') "
                        + "      && tstzrange(w.ws, w.we, '[)')";
        java.util.Map<String, Object> overall = jdbc.queryForMap(overallSql,
                new MapSqlParameterSource().addValue("cid", classroomId).addValue("uid", userId));
        long eligiblePairs = ((Number) overall.get("eligible_pairs")).longValue();
        long completedPairs = ((Number) overall.get("completed_pairs")).longValue();
        return new ClassroomAnalyticsResponse(
                null, null, null, null, null, null,          // detail-only fields
                rosterSize, assignedCount != null ? assignedCount : 0L,
                0L, 0L, 0L, 0L, null,                        // per-session counters
                null, null, null, null, null, null,           // detail-only metrics
                ratio(completedPairs, eligiblePairs),
                eligiblePairs == 0 ? null : eligiblePairs,
                eligiblePairs == 0 ? null : completedPairs, trends, HISTORY_COVERAGE);
    }

    private ClassroomAnalyticsResponse sessionAnalytics(Long classroomId, Long ownerId, Long sessionId,
            long rosterSize, long assignedCount, Instant now) {
        List<SessionMeta> all = assignedSessions(classroomId, ownerId, null, null, null, sessionId);
        SessionMeta meta = all.isEmpty() ? null : all.get(0);
        if (meta == null) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_GRADEBOOK_INVALID_QUERY);
        }
        SessionWindow w = windowOf(meta, now);
        Map<Long, Set<Long>> pairs = cohortPairs(classroomId, Map.of(meta.id(), w));
        Set<Long> cohort = pairs.getOrDefault(meta.id(), Set.of());
        Map<Long, Map<Long, AttemptState>> states =
                attemptStates(List.of(meta.id()), cohort);
        Map<Long, Map<Long, BestResult>> best = bestResults(List.of(meta.id()), cohort);

        long started = 0L;
        long submitted = 0L;
        List<BigDecimal> pcts = new ArrayList<>();
        for (Long uid : cohort) {
            AttemptState st = states.getOrDefault(meta.id(), Map.of()).get(uid);
            BestResult br = best.getOrDefault(meta.id(), Map.of()).get(uid);
            if (st != null) {
                started++;
                if (st.submitted() > 0) {
                    submitted++;
                }
            }
            if (br != null && br.percentage() != null) {
                pcts.add(br.percentage());
            }
        }
        long graded = best.getOrDefault(meta.id(), Map.of()).size();
        List<BigDecimal> sorted = new ArrayList<>(pcts);
        sorted.sort(BigDecimal::compareTo);
        List<Long> buckets = distribution(sorted);
        boolean windowed = SessionWindow.WINDOW.equals(w.state());
        // Nullability contract (Final Gate P2-01): completion and
        // notAttempted are COMPLETED-session metrics. For UPCOMING the
        // roster is prospective (nobody "hasn't attempted" an assignment
        // that has not opened); for DRAFT/CANCELLED/NOT_HELD there is no
        // taking window at all. Raw started/submitted/graded counters stay
        // exact (0 for prospective rosters).
        return new ClassroomAnalyticsResponse(meta.id(), meta.title(), meta.code(), meta.status(),
                w.state(), meta.startsAt(), rosterSize, assignedCount, cohort.size(), started,
                submitted, graded,
                windowed ? (cohort.size() - started) : null,
                windowed ? ratio(submitted, cohort.size()) : null,
                mean(pcts),
                sorted.isEmpty() ? null : sorted.get(0),
                sorted.isEmpty() ? null : sorted.get(sorted.size() - 1),
                median(sorted), buckets, null, null, null, null, HISTORY_COVERAGE);
    }

    /** 10 buckets: i = [i·10,(i+1)·10), bucket 9 = [90,100] (SessionStatistics rule). */
    private static List<Long> distribution(List<BigDecimal> sortedPcts) {
        TreeMap<Integer, Long> counts = new TreeMap<>();
        for (int i = 0; i < 10; i++) {
            counts.put(i, 0L);
        }
        for (BigDecimal pct : sortedPcts) {
            int bucket = pct.intValue() / 10; // floor; NUMERIC(7,4) values truncate safely
            if (bucket > 9) {
                bucket = 9; // exactly 100.00
            }
            counts.merge(bucket, 1L, Long::sum);
        }
        return new ArrayList<>(counts.values());
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        if (sorted.isEmpty()) {
            return null;
        }
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2).setScale(2, RoundingMode.HALF_UP);
        }
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    /** numerator/denominator·100 scale 2, NULL (never 0) when denominator = 0. */
    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    // ============================================================
    // GET /api/classrooms/joined/{id}/my-results — learner, self-scoped
    // ============================================================

    /**
     * Own outcomes for every non-DRAFT session assigned to a JOINED classroom
     * (uniform 404 for non-member/unknown — M2 no-oracle rule). After
     * leaving, this endpoint 404s with the rest of the class detail; the
     * learner's personal history remains available via {@code /api/attempts/my}
     * and the existing own-result endpoints.
     */
    @Transactional
    public PageResponse<LearnerMyResultsItem> myResults(Long userId, Long classroomId, int page, int size) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND));
        if (!memberRepository.existsByClassroomIdAndUserId(classroomId, userId)) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND);
        }
        sessionRepository.refreshLifecycleForCreator(classroom.getOwnerUserId(), Instant.now());
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        // Every assigned session except DRAFT (learner-safe rule, M2).
        List<SessionMeta> metas = jdbc.query(
                "SELECT s.id, s.code, s.title, s.status, s.starts_at, s.ends_at, s.opened_at, s.closed_at, "
                        + "s.exam_id, e.title AS exam_title "
                        + "FROM exam_session_classes esc "
                        + "JOIN exam_sessions s ON s.id = esc.exam_session_id "
                        + "JOIN exams e ON e.id = s.exam_id "
                        + "WHERE esc.classroom_id = :cid AND s.created_by = :owner AND s.status <> 'DRAFT' "
                        + "ORDER BY s.starts_at DESC, s.id DESC",
                new MapSqlParameterSource().addValue("cid", classroomId)
                        .addValue("owner", classroom.getOwnerUserId()),
                (rs, n) -> new SessionMeta(
                        rs.getLong("id"), rs.getString("code"), rs.getString("title"), rs.getString("status"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                        toInstant(rs.getTimestamp("opened_at")), toInstant(rs.getTimestamp("closed_at")),
                        rs.getLong("exam_id"), rs.getString("exam_title")));

        Instant now = Instant.now();
        Map<Long, SessionWindow> windows = new LinkedHashMap<>();
        for (SessionMeta m : metas) {
            windows.put(m.id(), windowOf(m, now));
        }
        Map<Long, Set<Long>> pairs = cohortPairs(classroomId, windows);
        Map<Long, Map<Long, AttemptState>> states =
                attemptStates(new ArrayList<>(windows.keySet()), Set.of(userId));
        Map<Long, Map<Long, BestResult>> best = bestResults(new ArrayList<>(windows.keySet()), Set.of(userId));

        List<LearnerMyResultsItem> items = new ArrayList<>();
        for (SessionMeta m : metas) {
            SessionWindow w = windows.get(m.id());
            AttemptState st = states.getOrDefault(m.id(), Map.of()).get(userId);
            GradebookCell cell = cellOf(w,
                    pairs.getOrDefault(m.id(), Set.of()).contains(userId), true,
                    st, best.getOrDefault(m.id(), Map.of()).get(userId));
            // Learner-facing attemptCount counts SUBMITTED/GRADED attempts —
            // parity with /results/me/best and /attempts/my semantics
            // (review M3-Domain P3-1); the teacher gradebook cell keeps the
            // all-attempts count.
            items.add(new LearnerMyResultsItem(m.id(), m.code(), m.title(), m.status(),
                    m.startsAt(), m.endsAt(), m.examTitle(), cell.status(), cell.score(),
                    cell.maxScore(), cell.percentage(), cell.bestAttemptId(),
                    st == null ? 0L : st.submitted()));
        }
        long totalElements = items.size();
        int totalPages = (int) ((totalElements + safeSize - 1) / safeSize);
        int safePage = Math.max(page, 0);
        if (totalElements > 0 && safePage >= totalPages) {
            safePage = totalPages - 1;
        }
        List<LearnerMyResultsItem> pageItems = items.subList(
                Math.toIntExact(safePage * (long) safeSize),
                Math.toIntExact(Math.min((safePage + 1L) * safeSize, totalElements)));
        return new PageResponse<>(pageItems, safePage, safeSize, totalElements, totalPages,
                "startsAt,desc");
    }
}
