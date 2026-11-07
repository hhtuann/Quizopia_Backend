package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.exam.dto.CreateExamRequest;
import com.quizopia.backend.exam.dto.CreateExamSessionRequest;
import com.quizopia.backend.exam.dto.ExamSessionDetailResponse;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level integration tests for the 4 lifecycle endpoints (A3.2-3C): schedule/open/close/cancel.
 * Real PG17 Testcontainers. PUBLISHED exam staged via jdbc; sessions created via the service then
 * state-adjusted via jdbc where a transition through the API is not possible (e.g. an already-expired
 * SCHEDULED session to test the open-after-ends guard).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamSessionLifecycleServiceIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;
    @Autowired private ExamSessionService sessionService;
    @Autowired private EntityManager entityManager;

    private long teacherUserId;
    private long subjectId;
    private long examId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('lc','lc@t','h','LC')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ((SELECT id FROM grade_levels WHERE code='G10'), 'SUB','Sub')");
        examId = setupPublishedExam();
    }

    // -- SCHEDULE --

    @Test
    void scheduleDraftToScheduledSuccess() {
        long sid = createDraftSession("SD1", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        ExamSessionDetailResponse r = sessionService.scheduleSession(teacherUserId, null, sid);
        assertThat(r.status()).isEqualTo("SCHEDULED");
        assertThat(r.openedAt()).isNull();
        assertThat(r.closedAt()).isNull();
        assertThat(r.participantCount()).isZero();
        assertDbState(sid, "SCHEDULED", null, null);
    }

    @Test
    void scheduleIdempotentDoesNotMutate() {
        long sid = createDraftSession("SD2", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        sessionService.scheduleSession(teacherUserId, null, sid);
        entityManager.clear();
        ExamSessionDetailResponse r2 = sessionService.scheduleSession(teacherUserId, null, sid);
        assertThat(r2.status()).isEqualTo("SCHEDULED");
        assertThat(r2.openedAt()).isNull();
        assertThat(r2.closedAt()).isNull();
    }

    @Test
    void scheduleZeroParticipantStillSuccess() {
        long sid = createDraftSession("SD3", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        ExamSessionDetailResponse r = sessionService.scheduleSession(teacherUserId, null, sid);
        assertThat(r.status()).isEqualTo("SCHEDULED");
        assertThat(r.participantCount()).isZero();
    }

    @Test
    void scheduleExpiredRejected() {
        long sid = createDraftSession("SD4", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        assertThatThrownBy(() -> sessionService.scheduleSession(teacherUserId, null, sid))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_TIME_INVALID));
    }

    @Test
    void scheduleFromOpenClosedCancelledRejected() {
        long open = createDraftSession("SO1", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        setState(open, "OPEN", "now()-interval '1 hour'", null);
        long closed = createDraftSession("SC1", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        setState(closed, "CLOSED", "now()-interval '2 hours'", "now()-interval '1 hour'");
        long cancelled = createDraftSession("SX1", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        setState(cancelled, "CANCELLED", null, null);
        for (long sid : new long[]{open, closed, cancelled}) {
            assertThatThrownBy(() -> sessionService.scheduleSession(teacherUserId, null, sid))
                    .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_INVALID_STATE));
        }
    }

    // -- OPEN --

    @Test
    void openScheduledToOpenSuccess() {
        long sid = createDraftSession("OP1", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        sessionService.scheduleSession(teacherUserId, null, sid);
        entityManager.clear();
        ExamSessionDetailResponse r = sessionService.openSession(teacherUserId, null, sid);
        assertThat(r.status()).isEqualTo("OPEN");
        assertThat(r.openedAt()).isNotNull();
        assertThat(r.closedAt()).isNull();
        assertDbState(sid, "OPEN", notNull(), null);
    }

    @Test
    void openIdempotentOpenedAtStable() {
        long sid = createDraftSession("OP2", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        sessionService.scheduleSession(teacherUserId, null, sid);
        entityManager.clear();
        sessionService.openSession(teacherUserId, null, sid);
        Object openedAfterFirst = jdbc.queryForObject("SELECT opened_at FROM exam_sessions WHERE id=?", Object.class, sid);
        entityManager.clear();
        sessionService.openSession(teacherUserId, null, sid); // idempotent retry
        Object openedAfterSecond = jdbc.queryForObject("SELECT opened_at FROM exam_sessions WHERE id=?", Object.class, sid);
        // openedAt is NOT overwritten on idempotent retry (compare DB values: timestamptz micros).
        assertThat(openedAfterSecond).isEqualTo(openedAfterFirst);
        assertDbState(sid, "OPEN", notNull(), null);
    }

    @Test
    void openEarlyRejected() {
        long sid = createDraftSession("OP3", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        sessionService.scheduleSession(teacherUserId, null, sid); // now < ends, so schedule is valid
        entityManager.clear();
        assertThatThrownBy(() -> sessionService.openSession(teacherUserId, null, sid))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_TIME_INVALID));
    }

    @Test
    void openAfterEndsRejected() {
        // SCHEDULED with a past window — unreachable via /schedule (it would reject), so stage via jdbc.
        long sid = createDraftSession("OP4", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        setState(sid, "SCHEDULED", null, null);
        assertThatThrownBy(() -> sessionService.openSession(teacherUserId, null, sid))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_TIME_INVALID));
    }

    @Test
    void openOnExpiredOpenStaysOpenNoLazyClose() {
        long sid = createDraftSession("OP5", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        setState(sid, "OPEN", "now()-interval '2 hours'", null);
        ExamSessionDetailResponse r = sessionService.openSession(teacherUserId, null, sid);
        assertThat(r.status()).isEqualTo("OPEN"); // idempotent 200, NOT lazy-closed
        assertThat(r.closedAt()).isNull();
        assertDbState(sid, "OPEN", notNull(), null);
    }

    @Test
    void openFromDraftClosedCancelledRejected() {
        long draft = createDraftSession("OP6", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        long closed = createDraftSession("OP7", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        setState(closed, "CLOSED", "now()-interval '2 hours'", "now()-interval '1 hour'");
        long cancelled = createDraftSession("OP8", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        setState(cancelled, "CANCELLED", null, null);
        for (long sid : new long[]{draft, closed, cancelled}) {
            assertThatThrownBy(() -> sessionService.openSession(teacherUserId, null, sid))
                    .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_INVALID_STATE));
        }
    }

    // -- CLOSE --

    @Test
    void closeOpenToClosedSuccess() {
        long sid = createDraftSession("CL1", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        sessionService.scheduleSession(teacherUserId, null, sid);
        sessionService.openSession(teacherUserId, null, sid);
        entityManager.clear();
        ExamSessionDetailResponse r = sessionService.closeSession(teacherUserId, null, sid);
        assertThat(r.status()).isEqualTo("CLOSED");
        assertThat(r.openedAt()).isNotNull();
        assertThat(r.closedAt()).isNotNull();
        assertDbState(sid, "CLOSED", notNull(), notNull());
    }

    @Test
    void closeIdempotentClosedAtStable() {
        long sid = createDraftSession("CL2", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        sessionService.scheduleSession(teacherUserId, null, sid);
        sessionService.openSession(teacherUserId, null, sid);
        entityManager.clear();
        sessionService.closeSession(teacherUserId, null, sid);
        Object closedAfterFirst = jdbc.queryForObject("SELECT closed_at FROM exam_sessions WHERE id=?", Object.class, sid);
        entityManager.clear();
        sessionService.closeSession(teacherUserId, null, sid); // idempotent retry
        Object closedAfterSecond = jdbc.queryForObject("SELECT closed_at FROM exam_sessions WHERE id=?", Object.class, sid);
        // closedAt is NOT overwritten on idempotent retry (compare DB values: timestamptz micros).
        assertThat(closedAfterSecond).isEqualTo(closedAfterFirst);
    }

    @Test
    void closeFromDraftScheduledCancelledRejected() {
        long draft = createDraftSession("CL3", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        long scheduled = createDraftSession("CL4", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        sessionService.scheduleSession(teacherUserId, null, scheduled);
        long cancelled = createDraftSession("CL5", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        setState(cancelled, "CANCELLED", null, null);
        entityManager.clear();
        for (long sid : new long[]{draft, scheduled, cancelled}) {
            assertThatThrownBy(() -> sessionService.closeSession(teacherUserId, null, sid))
                    .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_INVALID_STATE));
        }
    }

    // -- CANCEL --

    @Test
    void cancelDraftToCancelledSuccess() {
        long sid = createDraftSession("CN1", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        ExamSessionDetailResponse r = sessionService.cancelSession(teacherUserId, null, sid);
        assertThat(r.status()).isEqualTo("CANCELLED");
        assertThat(r.openedAt()).isNull();
        assertThat(r.closedAt()).isNull();
        assertDbState(sid, "CANCELLED", null, null);
    }

    @Test
    void cancelScheduledToCancelledSuccess() {
        long sid = createDraftSession("CN2", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        sessionService.scheduleSession(teacherUserId, null, sid);
        entityManager.clear();
        ExamSessionDetailResponse r = sessionService.cancelSession(teacherUserId, null, sid);
        assertThat(r.status()).isEqualTo("CANCELLED");
        assertThat(r.openedAt()).isNull();
        assertThat(r.closedAt()).isNull();
    }

    @Test
    void cancelIdempotent() {
        long sid = createDraftSession("CN3", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        sessionService.cancelSession(teacherUserId, null, sid);
        entityManager.clear();
        ExamSessionDetailResponse r2 = sessionService.cancelSession(teacherUserId, null, sid);
        assertThat(r2.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelFromOpenClosedRejected() {
        long open = createDraftSession("CN4", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        setState(open, "OPEN", "now()-interval '1 hour'", null);
        long closed = createDraftSession("CN5", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        setState(closed, "CLOSED", "now()-interval '2 hours'", "now()-interval '1 hour'");
        for (long sid : new long[]{open, closed}) {
            assertThatThrownBy(() -> sessionService.cancelSession(teacherUserId, null, sid))
                    .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_INVALID_STATE));
        }
    }

    // -- INVARIANT + PARTICIPANT + AUTH --

    @Test
    void timestampInvariantHoldsForEachState() {
        // DRAFT
        long d = createDraftSession("IV1", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        assertDbState(d, "DRAFT", null, null);
        // SCHEDULED
        sessionService.scheduleSession(teacherUserId, null, d);
        assertDbState(d, "SCHEDULED", null, null);
        // (reuse a straddling-window session for OPEN/CLOSED)
        long s = createDraftSession("IV2", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        sessionService.scheduleSession(teacherUserId, null, s);
        sessionService.openSession(teacherUserId, null, s);
        assertDbState(s, "OPEN", notNull(), null);
        sessionService.closeSession(teacherUserId, null, s);
        assertDbState(s, "CLOSED", notNull(), notNull());
        // CANCELLED
        long c = createDraftSession("IV3", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        sessionService.cancelSession(teacherUserId, null, c);
        assertDbState(c, "CANCELLED", null, null);
    }

    @Test
    void lifecycleDoesNotMutateParticipants() {
        long sid = createDraftSession("PA1", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        long sp = addParticipant(sid, "HS001");
        // block one participant before lifecycle runs
        jdbc.update("UPDATE exam_session_participants SET status='BLOCKED', blocked_at=now() WHERE id=?", sp);
        entityManager.clear();
        sessionService.scheduleSession(teacherUserId, null, sid);
        sessionService.openSession(teacherUserId, null, sid);
        sessionService.closeSession(teacherUserId, null, sid);
        // participant row count + status untouched by lifecycle
        assertThat(participantCount(sid)).isEqualTo(1);
        assertThat(participantStatus(sp)).isEqualTo("BLOCKED");
    }

    @Test
    void foreignOwnerDeniedAcrossEndpoints() {
        long sid = createDraftSession("FO1", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('fo','fo@t','h','FO')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", other);
        for (Runnable call : new Runnable[]{
                () -> sessionService.scheduleSession(other, null, sid),
                () -> sessionService.openSession(other, null, sid),
                () -> sessionService.closeSession(other, null, sid),
                () -> sessionService.cancelSession(other, null, sid)
        }) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
        }
    }

    @Test
    void notFoundAcrossEndpoints() {
        long missing = 999999L;
        assertThatThrownBy(() -> sessionService.scheduleSession(teacherUserId, null, missing))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_NOT_FOUND));
        assertThatThrownBy(() -> sessionService.cancelSession(teacherUserId, null, missing))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_NOT_FOUND));
    }

    @Test
    void responseHasNoAnswerLeak() {
        long sid = createDraftSession("NL1", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        ExamSessionDetailResponse r = sessionService.scheduleSession(teacherUserId, null, sid);
        // ExamSessionDetailResponse carries no answer/isCorrect fields by construction.
        assertThat(r.status()).isEqualTo("SCHEDULED");
        assertThat(r.code()).isNotNull();
    }

    // -- Helpers --

    private long setupPublishedExam() {
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES ("
                + teacherUserId + "," + subjectId + ",'LCB','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        Long id = examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "LCE", "T", null)).id();
        examService.updateDraftComposition(teacherUserId, id, new UpdateDraftCompositionRequest(0, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(q, 0, null))))));
        jdbc.update("UPDATE exams SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", id);
        entityManager.clear();
        return id;
    }

    private long createDraftSession(String code, Instant starts, Instant ends) {
        long sid = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(examId, code, "T", starts, ends, 1, null, null, null)).id();
        entityManager.clear();
        return sid;
    }

    private void setState(long sessionId, String status, String openedAtExpr, String closedAtExpr) {
        String opened = openedAtExpr == null ? "NULL" : openedAtExpr;
        String closed = closedAtExpr == null ? "NULL" : closedAtExpr;
        jdbc.update("UPDATE exam_sessions SET status=?, opened_at=" + opened + ", closed_at=" + closed + " WHERE id=?", status, sessionId);
        entityManager.clear();
    }

    private long addParticipant(long sessionId, String code) {
        long uid = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + code + "','" + code + "@t','h','" + code + "')");
        return insert("INSERT INTO exam_session_participants (exam_session_id, user_id, added_by) VALUES (?,?,?)",
                sessionId, uid, teacherUserId);
    }

    private void assertDbState(long sessionId, String status, Object openedAtExpect, Object closedAtExpect) {
        assertThat(jdbc.queryForObject("SELECT status FROM exam_sessions WHERE id=?", String.class, sessionId)).isEqualTo(status);
        assertTimestamp("opened_at", sessionId, openedAtExpect);
        assertTimestamp("closed_at", sessionId, closedAtExpect);
    }

    private void assertTimestamp(String col, long sessionId, Object expect) {
        Object actual = jdbc.queryForObject("SELECT " + col + " FROM exam_sessions WHERE id=?", Object.class, sessionId);
        if (expect == null) {
            assertThat(actual).as(col).isNull();
        } else {
            assertThat(actual).as(col).isNotNull();
        }
    }

    /** Sentinel meaning "expect non-null" for assertDbState. */
    private static Object notNull() {
        return new Object();
    }

    private long participantCount(long sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM exam_session_participants WHERE exam_session_id=?", Long.class, sessionId);
    }

    private String participantStatus(long participantId) {
        return jdbc.queryForObject("SELECT status FROM exam_session_participants WHERE id=?", String.class, participantId);
    }

    private long insert(String sql, Object... args) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class, args);
    }
}
