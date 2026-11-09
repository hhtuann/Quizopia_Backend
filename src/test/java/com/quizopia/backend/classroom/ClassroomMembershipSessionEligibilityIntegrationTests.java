package com.quizopia.backend.classroom;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.classroom.application.ClassroomMembershipService;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CLASS-M1 (issue #50): session eligibility and attempt-history invariants
 * around multi-class membership and self-leave.
 *
 * <ul>
 *   <li>A CLASS_RESTRICTED session assigned to TWO classrooms the learner
 *       belongs to is discovered exactly ONCE (EXISTS semantics), and one
 *       attempt per (user, session) is all the quota ever allows.</li>
 *   <li>Leaving revokes NEW eligibility through the membership gate, while
 *       historical attempts are never deleted.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@Transactional
class ClassroomMembershipSessionEligibilityIntegrationTests {

    @Autowired
    private AttemptService attemptService;
    @Autowired
    private ClassroomMembershipService membershipService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private long learnerId;
    private long teacherId;
    private long examId;
    private String tag;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-11-08T08:00:00Z");
        clock.setInstant(now);
        tag = java.util.UUID.randomUUID().toString().substring(0, 8);
        learnerId = insert("INSERT INTO users (username, email, password_hash, display_name, email_verified_at) "
                + "VALUES ('lrn" + tag + "','lrn" + tag + "@t.com','h','Learner " + tag + "',now())");
        assignRole(learnerId, "STUDENT");
        teacherId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('tch" + tag + "','tch" + tag + "@t.com','h','Teacher " + tag + "')");
        assignRole(teacherId, "TEACHER");

        long gl = insert("INSERT INTO grade_levels (code, name) VALUES ('GL" + tag + "','G')");
        long subj = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M" + tag + "','Math')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) "
                + "VALUES (" + teacherId + "," + subj + ",'B" + tag + "','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + tag + "'," + teacherId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) "
                + "VALUES (" + q + ",1,'SINGLE_CHOICE','Q1',1,'{}'::jsonb," + teacherId + ")");
        examId = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) "
                + "VALUES (" + teacherId + "," + subj + ",'E" + tag + "','Exam','PUBLISHED',now(),1)");
        long sec = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + examId + ",'S',0)");
        long eq = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + examId + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','Q1',1,0,'{}'::jsonb)");
        // Options are required for a STARTABLE SINGLE_CHOICE question
        // (validateSourceCardinality aborts the attempt without them).
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) "
                + "VALUES (" + eq + ",'A','Opt A',false,0),(" + eq + ",'B','Opt B',false,1),("
                + eq + ",'C','Opt C',true,2),(" + eq + ",'D','Opt D',false,3)");
    }

    @Test
    void sessionAssignedToTwoLearnerClassesIsDiscoveredOnce() {
        long classA = classroom("CA" + tag);
        long classB = classroom("CB" + tag);
        member(classA, learnerId);
        member(classB, learnerId);
        long session = classRestrictedSession("SH" + tag);
        assign(session, classA);
        assign(session, classB); // same session, second classroom

        var resp = attemptService.getAvailableSessions(learnerId);
        long matches = resp.items().stream()
                .filter(i -> i.exam().examId() == examId).count();
        assertThat(matches).as("one discovery row for a session shared by two member classrooms").isEqualTo(1);
        // Eligibility check is also single-verdict.
        assertThat(attemptService.getSessionEligibility(learnerId, session)).isNotNull();
    }

    @Test
    void sharedSessionAcrossTwoMemberClassesAllowsExactlyOneAttempt() {
        long classA = classroom("QA" + tag);
        long classB = classroom("QB" + tag);
        member(classA, learnerId);
        member(classB, learnerId);
        long session = classRestrictedSession("QS" + tag); // max_attempts = 1
        assign(session, classA);
        assign(session, classB);

        // Membership in TWO assigned classrooms must not double the quota:
        // start → submit → the second start is refused (ATTEMPT_MAX_REACHED)
        // and exactly ONE attempt row exists for (user, session).
        long attemptId = attemptService.startAttempt(learnerId, session,
                new StartAttemptRequest(null)).attemptId();
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), "
                + "submission_idempotency_key='K" + attemptId + "' WHERE id=" + attemptId);
        assertThatThrownBy(() -> attemptService.startAttempt(learnerId, session,
                new StartAttemptRequest(null)))
                .isInstanceOfSatisfying(AttemptException.class, ex ->
                        assertThat(ex.getErrorCode().name()).isEqualTo("ATTEMPT_MAX_REACHED"));
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_session_id=" + session
                        + " AND user_id=" + learnerId, Integer.class);
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    void leavingRevokesNewEligibilityButKeepsAttempts() {
        long classA = classroom("LV" + tag);
        member(classA, learnerId);
        long session = classRestrictedSession("SL" + tag);
        assign(session, classA);
        // One completed attempt exists (history that must survive leave).
        jdbc.update("INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, started_at, "
                + "deadline_at, submitted_at, submission_idempotency_key) VALUES (" + session + ","
                + learnerId + ",1,'SUBMITTED',now(),now()+interval '1 hour',now(),'m1-" + tag + "')");

        assertThat(attemptService.getAvailableSessions(learnerId).items().stream()
                .anyMatch(i -> i.exam().examId() == examId)).isTrue();

        membershipService.leaveClassroom(learnerId, classA);

        // New discovery: gone.
        assertThat(attemptService.getAvailableSessions(learnerId).items().stream()
                .noneMatch(i -> i.exam().examId() == examId)).isTrue();
        // New start: not eligible.
        assertThatThrownBy(() -> attemptService.getSessionEligibility(learnerId, session))
                .isInstanceOf(AttemptException.class);
        // History preserved (attempt row untouched).
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_session_id=" + session
                        + " AND user_id=" + learnerId, Integer.class);
        assertThat(attempts).isEqualTo(1);
        // exam_session_classes associations preserved (issue decision 7).
        Integer assigns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_session_classes WHERE exam_session_id=" + session, Integer.class);
        assertThat(assigns).isEqualTo(1);
    }

    // ============================================================
    // Helpers (fixture pattern from AvailableSessionQueryCountIntegrationTests)
    // ============================================================

    private long classroom(String code) {
        return insert("INSERT INTO classrooms (owner_user_id, code, name) VALUES ("
                + teacherId + ",'" + code + "','C-" + code + "')");
    }

    private void member(long classroomId, long userId) {
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES (" + classroomId + "," + userId + ")");
    }

    private long classRestrictedSession(String code) {
        Instant now = clock.instant();
        long session = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, "
                + "max_attempts, created_by, opened_at, visibility, duration_minutes) VALUES ("
                + examId + ",'" + code + "','S','OPEN','" + now.minusSeconds(3600) + "','"
                + now.plusSeconds(7200) + "',1," + teacherId + ",'" + now.minusSeconds(3600)
                + "','CLASS_RESTRICTED',60)");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES ("
                + learnerId + "," + session + "," + teacherId + ")");
        return session;
    }

    private void assign(long sessionId, long classroomId) {
        jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id) VALUES ("
                + sessionId + "," + classroomId + ")");
    }

    private void assignRole(long userId, String roleCode) {
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='" + roleCode + "'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + userId + "," + roleId + ")");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
