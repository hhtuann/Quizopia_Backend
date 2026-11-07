package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.application.ExamSessionParticipantService;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
@SuppressWarnings({"null"})
class ExamSessionParticipantServiceIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;
    @Autowired private ExamSessionService sessionService;
    @Autowired private ExamSessionParticipantService participantService;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private long teacherUserId;
    private long sessionId;
    private long student1, student2, student3;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ps','ps@t','h','PS')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        long subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ((SELECT id FROM grade_levels WHERE code='G10'), 'SUB','Sub')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherUserId + "," + subjectId + ",'QB','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        // Published exam
        Long examId = examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "PE", "T", null)).id();
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(0, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(q, 0, null))))));
        jdbc.update("UPDATE exams SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", examId);
        entityManager.clear();
        // Session DRAFT
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        sessionId = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(examId, "PS1", "Part Session", starts, ends, 1, null, null, null)).id();
        // Participant users
        student1 = createStudent("HS001", "Alice");
        student2 = createStudent("HS002", "Bob");
        student3 = createStudent("HS003", "Carol");
    }

    private long createStudent(String code, String name) {
        long uid = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + code + "','" + code + "@t','h','" + name + "')");
        return uid;
    }

    // -- ACCESS --

    @Test
    void listForeignSessionDenied() {
        long u2 = createUser("o1");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        assertThatThrownBy(() -> participantService.listParticipants(u2, sessionId, null, 0, 20, null))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
    }

    // -- LIST --

    @Test
    void listPaginatedWithFilter() {
        insertParticipants(student1, student2, student3);
        participantService.blockParticipant(teacherUserId, sessionId, getParticipantId(student1));
        // All
        PageResponse<ExamSessionParticipantResponse> all = participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, null);
        assertThat(all.items()).hasSize(3);
        // Filter ELIGIBLE
        PageResponse<ExamSessionParticipantResponse> elig = participantService.listParticipants(teacherUserId, sessionId, "ELIGIBLE", 0, 20, null);
        assertThat(elig.items()).hasSize(2);
        assertThat(elig.items()).allMatch(p -> "ELIGIBLE".equals(p.status()));
        // Filter BLOCKED
        PageResponse<ExamSessionParticipantResponse> blocked = participantService.listParticipants(teacherUserId, sessionId, "BLOCKED", 0, 20, null);
        assertThat(blocked.items()).hasSize(1);
        assertThat(blocked.items().get(0).userId()).isEqualTo(student1);
        assertThat(blocked.items().get(0).displayName()).isEqualTo("Alice");
        assertThat(blocked.items().get(0).studentCode()).isNull();
    }

    @Test
    void listNoAnswerLeak() {
        insertParticipants(student1);
        PageResponse<ExamSessionParticipantResponse> list = participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, null);
        assertThat(list.items()).hasSize(1);
        ExamSessionParticipantResponse p = list.items().get(0);
        assertThat(p.status()).isEqualTo("ELIGIBLE");
        assertThat(p.blockedAt()).isNull();
        assertThat(p.addedAt()).isNotNull();
    }

    // -- BLOCK / UNBLOCK --

    @Test
    void blockUnblockSuccess() {
        insertParticipants(student1);
        long pid = getParticipantId(student1);
        ExamSessionParticipantResponse blocked = participantService.blockParticipant(teacherUserId, sessionId, pid);
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.blockedAt()).isNotNull();
        ExamSessionParticipantResponse unblocked = participantService.unblockParticipant(teacherUserId, sessionId, pid);
        assertThat(unblocked.status()).isEqualTo("ELIGIBLE");
        assertThat(unblocked.blockedAt()).isNull();
    }

    @Test
    void blockIdempotent() {
        insertParticipants(student1);
        long pid = getParticipantId(student1);
        participantService.blockParticipant(teacherUserId, sessionId, pid);
        ExamSessionParticipantResponse r2 = participantService.blockParticipant(teacherUserId, sessionId, pid);
        assertThat(r2.status()).isEqualTo("BLOCKED");
    }

    @Test
    void unblockIdempotent() {
        insertParticipants(student1);
        long pid = getParticipantId(student1);
        ExamSessionParticipantResponse r = participantService.unblockParticipant(teacherUserId, sessionId, pid);
        assertThat(r.status()).isEqualTo("ELIGIBLE");
    }

    @Test
    void blockClosedStateRejected() {
        insertParticipants(student1);
        long pid = getParticipantId(student1);
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', opened_at=now()-interval '2 hours', closed_at=now() WHERE id=?", sessionId);
        entityManager.clear();
        assertThatThrownBy(() -> participantService.blockParticipant(teacherUserId, sessionId, pid))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_INVALID_STATE));
    }

    @Test
    void blockWrongSessionParticipantNotFound() {
        insertParticipants(student1);
        // Create a different session + participant, then try to block in the wrong session
        Instant starts = Instant.now().plusSeconds(3600);
        Long session2 = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(jdbc.queryForObject("SELECT exam_id FROM exam_sessions WHERE id=?", Long.class, sessionId), "PS2", "S2", starts, starts.plusSeconds(3600), 1, null, null, null)).id();
        long pid2 = insertParticipant(session2, student2);
        assertThatThrownBy(() -> participantService.blockParticipant(teacherUserId, sessionId, pid2))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_PARTICIPANT_NOT_FOUND));
    }

    @Test
    void blockOpenStateAllowed() {
        insertParticipants(student1);
        long pid = getParticipantId(student1);
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", sessionId);
        entityManager.clear();
        ExamSessionParticipantResponse r = participantService.blockParticipant(teacherUserId, sessionId, pid);
        assertThat(r.status()).isEqualTo("BLOCKED");
    }

    // -- Helpers --

    private long getParticipantId(long userId) {
        return jdbc.queryForObject("SELECT id FROM exam_session_participants WHERE exam_session_id=? AND user_id=?", Long.class, sessionId, userId);
    }

    private long insertParticipant(long sessionId, long userId) {
        return jdbc.queryForObject(
                "INSERT INTO exam_session_participants (exam_session_id, user_id, added_by) VALUES (?,?,?) RETURNING id",
                Long.class, sessionId, userId, teacherUserId);
    }

    private void insertParticipants(long... userIds) {
        for (long uid : userIds) {
            insertParticipant(sessionId, uid);
        }
    }

    // -- F1: query-count (no N+1; batch user-display query runs once) --

    @Test
    void listQueryCountDoesNotGrowWithPageSize() {
        Statistics stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // Scenario N=1: 1 participant
        insertParticipant(sessionId, student1);
        entityManager.clear();
        stats.clear();
        participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, null);
        long prepN1 = stats.getPrepareStatementCount();
        long userQueryExecN1 = userDisplayNameQueryExecutions(stats);

        // Scenario N=20: 19 more participants
        for (int i = 0; i < 19; i++) {
            insertParticipant(sessionId, createStudent("HS1" + i, "N" + i));
        }
        entityManager.clear();
        stats.clear();
        participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, null);
        long prepN20 = stats.getPrepareStatementCount();
        long userQueryExecN20 = userDisplayNameQueryExecutions(stats);

        // F1: the batch user-display query executes exactly ONCE per request (not per participant).
        // Measured: N=1 → 7 prepared stmt / 1 user-query exec; N=20 → 8 / 1; delta=1 (constant).
        assertThat(userQueryExecN1).as("user-display query executions at N=1").isEqualTo(1L);
        assertThat(userQueryExecN20).as("user-display query executions at N=20").isEqualTo(1L);
        // Total prepared statements must NOT grow proportionally with N (no per-participant query).
        long delta = Math.abs(prepN20 - prepN1);
        assertThat(delta).as("prepared-statement delta N=1(%d)->N=20(%d) must be constant, not proportional", prepN1, prepN20)
                .isLessThanOrEqualTo(2L);
    }

    private long userDisplayNameQueryExecutions(Statistics stats) {
        long sum = 0;
        for (String q : stats.getQueries()) {
            if (q != null && q.contains("displayName")) {
                sum += stats.getQueryStatistics(q).getExecutionCount();
            }
        }
        return sum;
    }

    // -- F2: sort allowlist (studentCode rejected; addedAt/status honored) --

    @Test
    void listRejectsStudentCodeSort() {
        insertParticipants(student1);
        // studentCode is not a participant-entity property → EXAM_VALIDATION_ERROR (400), not a 500.
        assertThatThrownBy(() -> participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, "studentCode,asc"))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
        assertThatThrownBy(() -> participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, "studentCode,desc"))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    @Test
    void listSortsByAddedAtAscAndDesc() {
        long p1 = insertParticipantAt(student1, "2026-01-01 10:00:00");
        long p2 = insertParticipantAt(student2, "2026-01-02 10:00:00");
        long p3 = insertParticipantAt(student3, "2026-01-03 10:00:00");
        entityManager.clear();

        List<Long> asc = participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, "addedAt,asc").items()
                .stream().map(ExamSessionParticipantResponse::id).toList();
        assertThat(asc).containsExactly(p1, p2, p3);

        List<Long> desc = participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, "addedAt,desc").items()
                .stream().map(ExamSessionParticipantResponse::id).toList();
        assertThat(desc).containsExactly(p3, p2, p1);
    }

    @Test
    void listSortsByStatusAscAndDesc() {
        long blocked = insertParticipantWithStatus(student1, "BLOCKED", "2026-01-01 10:00:00");
        long eligA = insertParticipantWithStatus(student2, "ELIGIBLE", "2026-01-02 10:00:00");
        long eligB = insertParticipantWithStatus(student3, "ELIGIBLE", "2026-01-03 10:00:00");
        entityManager.clear();

        // String ordering: BLOCKED < ELIGIBLE.
        List<String> asc = participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, "status,asc").items()
                .stream().map(ExamSessionParticipantResponse::status).toList();
        assertThat(asc).containsExactly("BLOCKED", "ELIGIBLE", "ELIGIBLE");

        List<String> desc = participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, "status,desc").items()
                .stream().map(ExamSessionParticipantResponse::status).toList();
        assertThat(desc).containsExactly("ELIGIBLE", "ELIGIBLE", "BLOCKED");
        // Sanity: the three rows are distinct.
        assertThat(blocked).isNotEqualTo(eligA);
        assertThat(eligA).isNotEqualTo(eligB);
    }

    private long insertParticipantAt(long userId, String addedAt) {
        return insertParticipantWithStatus(userId, "ELIGIBLE", addedAt);
    }

    private long insertParticipantWithStatus(long userId, String status, String addedAt) {
        String blockedAtExpr = "BLOCKED".equals(status) ? "'" + addedAt + "'" : "NULL";
        return jdbc.queryForObject(
                "INSERT INTO exam_session_participants (exam_session_id, user_id, status, added_by, added_at, blocked_at, version) "
                        + "VALUES (?,?,?,?,?," + blockedAtExpr + ",0) RETURNING id",
                Long.class, sessionId, userId, status, teacherUserId, Timestamp.valueOf(addedAt));
    }

    private long createUser(String prefix) {
        return insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + prefix + "','" + prefix + "@t','h','" + prefix + "')");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
