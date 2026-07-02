package com.hhtuann.backend.exam;

import com.hhtuann.backend.exam.application.ExamService;
import com.hhtuann.backend.exam.application.ExamSessionParticipantService;
import com.hhtuann.backend.exam.application.ExamSessionService;
import com.hhtuann.backend.exam.domain.model.ExamSessionStatus;
import com.hhtuann.backend.exam.dto.*;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.hhtuann.backend.exam.exception.ExamErrorCode;
import com.hhtuann.backend.exam.exception.ExamException;
import com.hhtuann.backend.question.dto.PageResponse;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamSessionParticipantServiceIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;
    @Autowired private ExamSessionService sessionService;
    @Autowired private ExamSessionParticipantService participantService;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private long teacherUserId;
    private long schoolId;
    private long sessionId;
    private long student1, student2, student3;
    private long crossSchoolStudent;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ps','ps@t','h','PS')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('PS','Part School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'PSC')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp + ",'QB','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        // Published exam
        Long examId = examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "PE", "T", null)).id();
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(1, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(q, 0, null))))));
        long v1 = jdbc.queryForObject("SELECT id FROM exam_versions WHERE exam_id=? AND status='DRAFT'", Long.class, examId);
        jdbc.update("UPDATE exam_versions SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", v1);
        jdbc.update("UPDATE exams SET status='READY' WHERE id=?", examId);
        entityManager.clear();
        // Session DRAFT
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        sessionId = sessionService.createSession(teacherUserId,
                new CreateExamSessionRequest(examId, 1, "PS1", "Part Session", starts, ends, 1)).id();
        // Students same school
        student1 = createStudent("HS001", "Alice");
        student2 = createStudent("HS002", "Bob");
        student3 = createStudent("HS003", "Carol");
        // Cross-school student
        long otherSchool = insert("INSERT INTO schools (code, name) VALUES ('XS','Cross')");
        long gl2 = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + otherSchool + ",'GL','G')");
        long xUser = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('xu','xu@t','h','XU')");
        crossSchoolStudent = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + xUser + "," + otherSchool + ",'XS001')");
    }

    private long createStudent(String code, String name) {
        long uid = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + code + "','" + code + "@t','h','" + name + "')");
        return insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + uid + "," + schoolId + ",'" + code + "')");
    }

    // -- ADD --

    @Test
    void bulkAddValidSuccess() {
        AddParticipantsResponse r = participantService.addParticipants(teacherUserId, sessionId,
                new AddParticipantsRequest(List.of(student1, student2)));
        assertThat(r.added()).isEqualTo(2);
        assertThat(r.duplicated()).isEmpty();
        assertThat(r.invalid()).isEmpty();
    }

    @Test
    void duplicateInRequestClassified() {
        AddParticipantsResponse r = participantService.addParticipants(teacherUserId, sessionId,
                new AddParticipantsRequest(List.of(student1, student1)));
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.duplicated()).containsExactly(student1);
    }

    @Test
    void duplicateAlreadyInDbClassified() {
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
        AddParticipantsResponse r = participantService.addParticipants(teacherUserId, sessionId,
                new AddParticipantsRequest(List.of(student1)));
        assertThat(r.added()).isZero();
        assertThat(r.duplicated()).containsExactly(student1);
    }

    @Test
    void missingAndCrossSchoolInvalid() {
        AddParticipantsResponse r = participantService.addParticipants(teacherUserId, sessionId,
                new AddParticipantsRequest(List.of(999999L, crossSchoolStudent)));
        assertThat(r.added()).isZero();
        assertThat(r.invalid()).containsExactlyInAnyOrder(999999L, crossSchoolStudent);
    }

    @Test
    void mixedClassification() {
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
        AddParticipantsResponse r = participantService.addParticipants(teacherUserId, sessionId,
                new AddParticipantsRequest(List.of(student1, student2, 999999L, crossSchoolStudent)));
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.duplicated()).containsExactly(student1);
        assertThat(r.invalid()).containsExactlyInAnyOrder(999999L, crossSchoolStudent);
    }

    @Test
    void addOpenStateRejected() {
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", sessionId);
        entityManager.clear();
        assertThatThrownBy(() -> participantService.addParticipants(teacherUserId, sessionId,
                new AddParticipantsRequest(List.of(student1))))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_INVALID_STATE));
    }

    @Test
    void addForeignSessionDenied() {
        long u2 = createUser("o1");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u2 + "," + schoolId + ",'TC2')");
        assertThatThrownBy(() -> participantService.addParticipants(u2, sessionId,
                new AddParticipantsRequest(List.of(student1))))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
    }

    // -- LIST --

    @Test
    void listPaginatedWithFilter() {
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1, student2, student3)));
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
        assertThat(blocked.items().get(0).studentProfileId()).isEqualTo(student1);
        assertThat(blocked.items().get(0).displayName()).isEqualTo("Alice");
        assertThat(blocked.items().get(0).studentCode()).isEqualTo("HS001");
    }

    @Test
    void listNoAnswerLeak() {
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
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
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
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
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
        long pid = getParticipantId(student1);
        participantService.blockParticipant(teacherUserId, sessionId, pid);
        ExamSessionParticipantResponse r2 = participantService.blockParticipant(teacherUserId, sessionId, pid);
        assertThat(r2.status()).isEqualTo("BLOCKED");
    }

    @Test
    void unblockIdempotent() {
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
        long pid = getParticipantId(student1);
        ExamSessionParticipantResponse r = participantService.unblockParticipant(teacherUserId, sessionId, pid);
        assertThat(r.status()).isEqualTo("ELIGIBLE");
    }

    @Test
    void blockClosedStateRejected() {
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
        long pid = getParticipantId(student1);
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', opened_at=now()-interval '2 hours', closed_at=now() WHERE id=?", sessionId);
        entityManager.clear();
        assertThatThrownBy(() -> participantService.blockParticipant(teacherUserId, sessionId, pid))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_SESSION_INVALID_STATE));
    }

    @Test
    void blockWrongSessionParticipantNotFound() {
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
        // Create a different session + participant, then try to block in the wrong session
        Instant starts = Instant.now().plusSeconds(3600);
        Long session2 = sessionService.createSession(teacherUserId,
                new CreateExamSessionRequest(jdbc.queryForObject("SELECT exam_id FROM exam_versions WHERE id=(SELECT exam_version_id FROM exam_sessions WHERE id=?)", Long.class, sessionId), 1, "PS2", "S2", starts, starts.plusSeconds(3600), 1)).id();
        participantService.addParticipants(teacherUserId, session2, new AddParticipantsRequest(List.of(student2)));
        long pid2 = jdbc.queryForObject("SELECT id FROM exam_session_participants WHERE exam_session_id=? AND student_profile_id=?", Long.class, session2, student2);
        assertThatThrownBy(() -> participantService.blockParticipant(teacherUserId, sessionId, pid2))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_PARTICIPANT_NOT_FOUND));
    }

    @Test
    void blockOpenStateAllowed() {
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
        long pid = getParticipantId(student1);
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", sessionId);
        entityManager.clear();
        ExamSessionParticipantResponse r = participantService.blockParticipant(teacherUserId, sessionId, pid);
        assertThat(r.status()).isEqualTo("BLOCKED");
    }

    // -- Helpers --

    private long getParticipantId(long studentProfileId) {
        return jdbc.queryForObject("SELECT id FROM exam_session_participants WHERE exam_session_id=? AND student_profile_id=?", Long.class, sessionId, studentProfileId);
    }

    // -- F1: query-count (no N+1; batch user-display query runs once) --

    @Test
    void listQueryCountDoesNotGrowWithPageSize() {
        Statistics stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // Scenario N=1: 1 participant
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
        entityManager.clear();
        stats.clear();
        participantService.listParticipants(teacherUserId, sessionId, null, 0, 20, null);
        long prepN1 = stats.getPrepareStatementCount();
        long userQueryExecN1 = userDisplayNameQueryExecutions(stats);

        // Scenario N=20: 19 more participants
        List<Long> more = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            more.add(createStudent("HS1" + i, "N" + i));
        }
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(more));
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
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(student1)));
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

    private long insertParticipantAt(long studentProfileId, String addedAt) {
        return insertParticipantWithStatus(studentProfileId, "ELIGIBLE", addedAt);
    }

    private long insertParticipantWithStatus(long studentProfileId, String status, String addedAt) {
        String blockedAtExpr = "BLOCKED".equals(status) ? "'" + addedAt + "'" : "NULL";
        return jdbc.queryForObject(
                "INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, status, added_by, added_at, blocked_at, version) "
                        + "VALUES (?,?,?,?,?,?," + blockedAtExpr + ",0) RETURNING id",
                Long.class, schoolId, sessionId, studentProfileId, status, teacherUserId, Timestamp.valueOf(addedAt));
    }

    private long createUser(String prefix) {
        return insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + prefix + "','" + prefix + "@t','h','" + prefix + "')");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
