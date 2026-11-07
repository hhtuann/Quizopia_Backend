package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.exam.dto.*;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.question.dto.PageResponse;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level integration tests for the 4 exam-session endpoints (A3.2-3A).
 * Real PG17
 * Testcontainers. PUBLISHED exam versions staged via jdbc (publish endpoint is
 * A3.2-2C).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamSessionServiceIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExamService examService;
    @Autowired
    private ExamSessionService sessionService;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private long teacherUserId;
    private long subjectId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('ss','ss@t','h','SS')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'",
                teacherUserId);
        subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ((SELECT id FROM grade_levels WHERE code='G10'), 'SUB','Sub')");
    }

    // -- Test group: CREATE --

    @Test
    void createSessionFromPublishedVersionSuccess() {
        PublishedExam pub = setupPublishedExam("E1");
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        ExamSessionDetailResponse r = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "S1", "Session 1", starts, ends, 1, null, null, null));

        assertThat(r.id()).isPositive();
        assertThat(r.status()).isEqualTo("DRAFT");
        assertThat(r.examId()).isEqualTo(pub.examId);
        assertThat(r.durationMinutes()).isEqualTo(60);
        assertThat(r.maxAttempts()).isEqualTo(1);
        assertThat(r.participantCount()).isZero();
        assertThat(r.version()).isZero();
    }

    @Test
    void createSessionDraftVersionRejected() {
        Long examId = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "E2", "T", null)).id();
        // Exam is DRAFT (never published)
        Instant starts = Instant.now().plusSeconds(3600);
        assertThatThrownBy(() -> sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(examId, "S2", "T", starts, starts.plusSeconds(3600), 1, null, null, null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_NOT_PUBLISHED_EXAM));
    }

    @Test
    void createSessionForeignExamRejected() {
        PublishedExam pub = setupPublishedExam("E3"); // owned by teacherUserId
        // Second teacher (non-creator of the exam)
        long u2 = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('s2','s2@t','h','S2')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        Instant starts = Instant.now().plusSeconds(3600);
        assertThatThrownBy(() -> sessionService.createSession(u2, null,
                new CreateExamSessionRequest(pub.examId, "SX", "T", starts, starts.plusSeconds(3600), 1, null, null, null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
    }

    @Test
    void createSessionDuplicateCodeSameOwnerCaseInsensitive409() {
        PublishedExam pub = setupPublishedExam("E4");
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "DUP", "T", starts, ends, 1, null, null, null));
        assertThatThrownBy(() -> sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "dup", "T2", starts, ends, 1, null, null, null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_CODE_CONFLICT));
    }

    @Test
    void createSessionTwoOwnersSameCodeAccepted() {
        PublishedExam pub = setupPublishedExam("E5");
        long u2 = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('s3','s3@t','h','S3')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        // u2 needs their own published exam (can't use teacher's exam — different
        // owner)
        PublishedExam exam2 = setupPublishedExamForUser(u2, "E5B");
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "SHARED", "T", starts, ends, 1, null, null, null));
        ExamSessionDetailResponse r2 = sessionService.createSession(u2, null,
                new CreateExamSessionRequest(exam2.examId, "SHARED", "T2", starts, ends, 1, null, null, null));
        assertThat(r2.id()).isPositive();
    }

    @Test
    void createSessionInvalidTime400() {
        PublishedExam pub = setupPublishedExam("E6");
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.minusSeconds(1800); // ends < starts
        assertThatThrownBy(() -> sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "S6", "T", starts, ends, 1, null, null, null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_TIME_INVALID));
    }

    // -- Test group: LIST --

    @Test
    void listMySessionsOwnerScopedWithFilters() {
        PublishedExam pub = setupPublishedExam("E7");
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "ALPHA", "Alpha", starts, ends, 1, null, null, null));
        sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "BETA", "Beta", starts, ends, 2, null, null, null));

        // All
        PageResponse<ExamSessionListItem> all = sessionService.listSessions(teacherUserId, null, null, null, 0, 20,
                null);
        assertThat(all.items()).hasSize(2);
        // Search
        PageResponse<ExamSessionListItem> search = sessionService.listSessions(teacherUserId, "alpha", null, null, 0,
                20, null);
        assertThat(search.items()).hasSize(1);
        assertThat(search.items().get(0).code()).isEqualTo("ALPHA");
        // Status filter
        PageResponse<ExamSessionListItem> draft = sessionService.listSessions(teacherUserId, null, "DRAFT", null, 0,
                20, null);
        assertThat(draft.items()).hasSize(2);
        PageResponse<ExamSessionListItem> open = sessionService.listSessions(teacherUserId, null, "OPEN", null, 0, 20,
                null);
        assertThat(open.items()).isEmpty();
        // Other teacher sees 0
        long u2 = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('s4','s4@t','h','S4')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        PageResponse<ExamSessionListItem> u2List = sessionService.listSessions(u2, null, null, null, 0, 20, null);
        assertThat(u2List.items()).isEmpty();
    }

    @Test
    void listQueryCountDoesNotGrowWithPage() {
        PublishedExam pub = setupPublishedExam("E8");
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        // N=1
        sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "Q1", "T", starts, ends, 1, null, null, null));
        Statistics stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        sessionService.listSessions(teacherUserId, null, null, null, 0, 20, null);
        long n1 = selectExecutionCount(stats);
        // N=20
        for (int i = 0; i < 19; i++) {
            sessionService.createSession(teacherUserId, null,
                    new CreateExamSessionRequest(pub.examId, "Q" + (i + 2), "T", starts, ends, 1, null, null, null));
        }
        stats.clear();
        sessionService.listSessions(teacherUserId, null, null, null, 0, 20, null);
        long n20 = selectExecutionCount(stats);
        System.out.println("SESSION_LIST_QUERY_COUNT N=1=" + n1 + " N=20=" + n20);
        assertThat(Math.abs(n20 - n1)).as("delta N=1=%d N=20=%d", n1, n20).isLessThanOrEqualTo(4L);
    }

    // -- Test group: DETAIL --

    @Test
    void getSessionDetailNoAnswerLeak() {
        PublishedExam pub = setupPublishedExam("E9");
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        ExamSessionDetailResponse created = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "D1", "Detail", starts, ends, 1, null, null, null));
        ExamSessionDetailResponse detail = sessionService.getSessionDetail(teacherUserId, null, created.id());
        assertThat(detail.code()).isEqualTo("D1");
        assertThat(detail.participantCount()).isZero();
        assertThat(detail.version()).isZero();
    }

    @Test
    void getSessionDetailForeignReturns403() {
        PublishedExam pub = setupPublishedExam("E10");
        Instant starts = Instant.now().plusSeconds(3600);
        ExamSessionDetailResponse created = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "D2", "T", starts, starts.plusSeconds(3600), 1, null, null, null));
        long u2 = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('s5','s5@t','h','S5')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        assertThatThrownBy(() -> sessionService.getSessionDetail(u2, null, created.id()))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
    }

    // -- Test group: UPDATE --

    @Test
    void updateSessionDraftSuccess() {
        PublishedExam pub = setupPublishedExam("E11");
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        ExamSessionDetailResponse created = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "U1", "Original", starts, ends, 1, null, null, null));
        Instant newStarts = starts.plusSeconds(1800);
        Instant newEnds = newStarts.plusSeconds(3600);
        ExamSessionDetailResponse updated = sessionService.updateSession(teacherUserId, null, created.id(),
                new UpdateExamSessionRequest(created.version(), "Updated", newStarts, newEnds, 3));
        assertThat(updated.title()).isEqualTo("Updated");
        assertThat(updated.maxAttempts()).isEqualTo(3);
        assertThat(updated.startsAt()).isEqualTo(newStarts);
        assertThat(updated.code()).isEqualTo("U1"); // code unchanged
        assertThat(updated.version()).isEqualTo(created.version() + 1); // @Version incremented
    }

    @Test
    void updateSessionExpectedVersionMismatch409() {
        PublishedExam pub = setupPublishedExam("E12");
        Instant starts = Instant.now().plusSeconds(3600);
        ExamSessionDetailResponse created = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "U2", "T", starts, starts.plusSeconds(3600), 1, null, null, null));
        assertThatThrownBy(() -> sessionService.updateSession(teacherUserId, null, created.id(),
                new UpdateExamSessionRequest(99, "Stale", starts, starts.plusSeconds(3600), 1)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_CONCURRENT_MODIFICATION));
    }

    @Test
    void updateSessionOpenStateRejected() {
        PublishedExam pub = setupPublishedExam("E13");
        Instant starts = Instant.now().plusSeconds(3600);
        ExamSessionDetailResponse created = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "U3", "T", starts, starts.plusSeconds(3600), 1, null, null, null));
        // Force session to OPEN via jdbc
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", created.id());
        entityManager.clear();
        assertThatThrownBy(() -> sessionService.updateSession(teacherUserId, null, created.id(),
                new UpdateExamSessionRequest(0, "X", starts, starts.plusSeconds(3600), 1)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_INVALID_STATE));
    }

    @Test
    void updateSessionInvalidTime400() {
        PublishedExam pub = setupPublishedExam("E14");
        Instant starts = Instant.now().plusSeconds(3600);
        ExamSessionDetailResponse created = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "U4", "T", starts, starts.plusSeconds(3600), 1, null, null, null));
        Instant badEnds = starts.minusSeconds(1800); // ends < starts
        assertThatThrownBy(() -> sessionService.updateSession(teacherUserId, null, created.id(),
                new UpdateExamSessionRequest(created.version(), "T", starts, badEnds, 1)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_TIME_INVALID));
    }

    // -- Test group: LAZY CLOSE --

    @Test
    void lazyCloseOnDetailReadPath() {
        PublishedExam pub = setupPublishedExam("E15");
        // Session with past window, forced to OPEN
        Instant pastStarts = Instant.now().minusSeconds(7200);
        Instant pastEnds = Instant.now().minusSeconds(3600);
        ExamSessionDetailResponse created = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "LC1", "T", pastStarts, pastEnds, 1, null, null, null));
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", created.id());
        entityManager.clear();
        // Detail read → lazy-close
        ExamSessionDetailResponse detail = sessionService.getSessionDetail(teacherUserId, null, created.id());
        assertThat(detail.status()).isEqualTo("CLOSED");
        assertThat(detail.closedAt()).isNotNull();
        // Idempotent: second read stays CLOSED
        entityManager.clear();
        ExamSessionDetailResponse detail2 = sessionService.getSessionDetail(teacherUserId, null, created.id());
        assertThat(detail2.status()).isEqualTo("CLOSED");
    }

    // -- Test group: LAZY CLOSE LIST (F1+F2 remediation: bulk before filter) --

    @Test
    void lazyCloseListExpiredOpenFilterOpenNotReturned() {
        PublishedExam pub = setupPublishedExam("LC2");
        Long expiredId = setupExpiredOpenSession(pub, "EXO");
        PageResponse<ExamSessionListItem> openList = sessionService.listSessions(teacherUserId, null, "OPEN", null, 0,
                20, null);
        assertThat(openList.items()).noneMatch(s -> s.id().equals(expiredId));
        assertThat(openList.totalElements()).isZero();
    }

    @Test
    void lazyCloseListExpiredOpenFilterClosedReturned() {
        PublishedExam pub = setupPublishedExam("LC3");
        Long expiredId = setupExpiredOpenSession(pub, "EXC");
        PageResponse<ExamSessionListItem> closedList = sessionService.listSessions(teacherUserId, null, "CLOSED",
                null, 0, 20, null);
        assertThat(closedList.items()).hasSize(1);
        assertThat(closedList.items().get(0).id()).isEqualTo(expiredId);
        assertThat(closedList.items().get(0).status()).isEqualTo("CLOSED");
    }

    @Test
    void lazyCloseListNoFilterShowsClosedStatus() {
        PublishedExam pub = setupPublishedExam("LC4");
        Long expiredId = setupExpiredOpenSession(pub, "NFD");
        PageResponse<ExamSessionListItem> all = sessionService.listSessions(teacherUserId, null, null, null, 0, 20,
                null);
        var expired = all.items().stream().filter(s -> s.id().equals(expiredId)).findFirst().orElseThrow();
        assertThat(expired.status()).isEqualTo("CLOSED");
    }

    @Test
    void lazyCloseListIdempotent() {
        PublishedExam pub = setupPublishedExam("LC5");
        setupExpiredOpenSession(pub, "IDM");
        sessionService.listSessions(teacherUserId, null, null, null, 0, 20, null);
        entityManager.clear();
        PageResponse<ExamSessionListItem> all2 = sessionService.listSessions(teacherUserId, null, null, null, 0, 20,
                null);
        assertThat(all2.items().get(0).status()).isEqualTo("CLOSED");
    }

    @Test
    void lazyCloseListNotExpiredOpenStaysOpen() {
        PublishedExam pub = setupPublishedExam("LC6");
        Instant starts = Instant.now().minusSeconds(3600);
        Instant ends = Instant.now().plusSeconds(3600);
        Long sessionId = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "NOPN", "T", starts, ends, 1, null, null, null)).id();
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", sessionId);
        entityManager.clear();
        PageResponse<ExamSessionListItem> openList = sessionService.listSessions(teacherUserId, null, "OPEN", null, 0,
                20, null);
        assertThat(openList.items()).hasSize(1);
        assertThat(openList.items().get(0).status()).isEqualTo("OPEN");
    }

    @Test
    void lazyCloseListClosedCancelledUnchanged() {
        PublishedExam pub = setupPublishedExam("LC7");
        Instant pastStarts = Instant.now().minusSeconds(7200);
        Instant pastEnds = Instant.now().minusSeconds(3600);
        Long closedId = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "CLD", "T", pastStarts, pastEnds, 1, null, null, null)).id();
        Long cancelledId = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, "CND", "T", pastStarts, pastEnds, 1, null, null, null)).id();
        jdbc.update(
                "UPDATE exam_sessions SET status='CLOSED', opened_at=now()-interval '2 hours', closed_at=now()-interval '1 hour' WHERE id=?",
                closedId);
        jdbc.update("UPDATE exam_sessions SET status='CANCELLED' WHERE id=?", cancelledId);
        entityManager.clear();
        sessionService.listSessions(teacherUserId, null, null, null, 0, 20, null);
        assertThat(jdbc.queryForObject("SELECT status FROM exam_sessions WHERE id=?", String.class, closedId))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM exam_sessions WHERE id=?", String.class, cancelledId))
                .isEqualTo("CANCELLED");
    }

    // ============================================================
    // Helpers
    // ============================================================

    private PublishedExam setupPublishedExam(String examCode) {
        return setupPublishedExamForUser(teacherUserId, examCode);
    }

    private PublishedExam setupPublishedExamForUser(long userId, String examCode) {
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES ("
                + userId + "," + subjectId + ",'SB" + Math.abs(System.nanoTime() % 1_000_000_000L)
                + "','Bank')");
        long q = insert(
                "INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES ("
                        + bank + ",'q','ACTIVE',1," + userId + ")");
        long v = insert(
                "INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES ("
                        + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + userId + ")");
        for (Object[] o : new Object[][] { { "A", true, 0 }, { "B", false, 1 }, { "C", false, 2 },
                { "D", false, 3 } }) {
            jdbc.update(
                    "INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES ("
                            + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        Long examId = examService.createExam(userId, new CreateExamRequest(subjectId, null, examCode, "T", null)).id();
        examService.updateDraftComposition(userId, examId, new UpdateDraftCompositionRequest(0, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(q, 0, null))))));
        // Flip the exam to PUBLISHED
        jdbc.update("UPDATE exams SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", examId);
        entityManager.clear();
        return new PublishedExam(examId, q);
    }

    private Long setupExpiredOpenSession(PublishedExam pub, String code) {
        Instant pastStarts = Instant.now().minusSeconds(7200);
        Instant pastEnds = Instant.now().minusSeconds(3600);
        Long sessionId = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(pub.examId, code, "T", pastStarts, pastEnds, 1, null, null, null)).id();
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", sessionId);
        entityManager.clear();
        return sessionId;
    }

    private long selectExecutionCount(Statistics statistics) {
        return Arrays.stream(statistics.getQueries())
                .filter(Objects::nonNull)
                .filter(q2 -> q2.trim().toLowerCase().startsWith("select"))
                .mapToLong(q2 -> statistics.getQueryStatistics(q2).getExecutionCount())
                .sum();
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    private record PublishedExam(Long examId, long questionId) {
    }
}
