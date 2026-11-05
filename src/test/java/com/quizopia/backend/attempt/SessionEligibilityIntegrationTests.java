package com.quizopia.backend.attempt;

import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP integration tests for {@code GET /api/exam-sessions/{sessionId}/eligibility}
 * (P2-D, issue #20): the server-authorized pre-flight behind a shared session
 * link. The eligibility policy must mirror discovery/start exactly — PUBLIC
 * for authenticated platform users, CLASS_RESTRICTED for assigned-classroom
 * members with an ACTIVE workspace membership, BLOCKED denied, lifecycle and
 * attempt-quota states surfaced without leaking creator-only metadata.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class SessionEligibilityIntegrationTests {

    private static final String ENDPOINT = "/api/exam-sessions/{sessionId}/eligibility";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private long studentUserId;
    private long schoolId;
    private long studentProfileId;
    private long examId;
    private long publicSessionId;
    private long classRestrictedSessionId;
    private long classroomId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-10T08:00:00Z");
        clock.setInstant(now);
        String tag = java.util.UUID.randomUUID().toString().substring(0, 6);

        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('sel" + tag + "','sel" + tag + "@t.com','h','Student')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", studentUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('SE" + tag + "','School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES ("
                + schoolId + "," + gl + ",'M','Math')");
        long teacherProfileId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + studentUserId + "," + schoolId + ",'TC" + tag + "')");
        studentProfileId = insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                + "VALUES (" + studentUserId + "," + schoolId + ",'SC" + tag + "')");

        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) "
                + "VALUES (" + schoolId + "," + subj + "," + teacherProfileId + ",'B','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + studentUserId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','Q1',1,'{}'::jsonb," + studentUserId + ")");
        examId = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title, status, "
                + "published_at, total_points) VALUES (" + schoolId + "," + subj + "," + teacherProfileId
                + ",'E','Exam','PUBLISHED',now(),1)");
        insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, "
                + "published_at, created_by) VALUES (" + schoolId + "," + examId + ",1,'PUBLISHED',1,now()," + studentUserId + ")");
        long sec = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + examId + ",'S',0)");
        long eq = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + examId + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','Q1',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) "
                + "VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");

        publicSessionId = insert("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, code, title, status, "
                + "starts_at, ends_at, max_attempts, created_by, opened_at, visibility, duration_minutes) VALUES ("
                + schoolId + "," + examId + "," + teacherProfileId + ",'PUB','Public Session','OPEN','"
                + now.minusSeconds(3600) + "','" + now.plusSeconds(7200)
                + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "','PUBLIC',60)");
        classRestrictedSessionId = insert("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, code, title, status, "
                + "starts_at, ends_at, max_attempts, created_by, opened_at, visibility, duration_minutes) VALUES ("
                + schoolId + "," + examId + "," + teacherProfileId + ",'CLS','Class Session','OPEN','"
                + now.minusSeconds(3600) + "','" + now.plusSeconds(7200)
                + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "','CLASS_RESTRICTED',60)");
        classroomId = insert("INSERT INTO classrooms (school_id, owner_teacher_id, owner_user_id, code, name) "
                + "VALUES (" + schoolId + "," + teacherProfileId
                + ",(SELECT user_id FROM teacher_profiles WHERE id=" + teacherProfileId + "),'CLS-A','Class A')");
    }

    // ============================================================
    // Auth / anti-enumeration
    // ============================================================

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(ENDPOINT, publicSessionId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingSession_returns404() throws Exception {
        mockMvc.perform(get(ENDPOINT, 999999L).with(jwtFor(studentUserId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXAM_SESSION_NOT_FOUND"));
    }

    @Test
    void permissionlessUser_returns403() throws Exception {
        long noRole = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('sel-nr','sel-nr@t.com','h','No Role')");
        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(noRole)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
    }

    // ============================================================
    // Visibility policy (mirrors discovery/start)
    // ============================================================

    @Test
    void publicSession_eligiblePlatformUser_canStart() throws Exception {
        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value((int) publicSessionId))
                .andExpect(jsonPath("$.state").value("CAN_START"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.canStartNow").value(true))
                .andExpect(jsonPath("$.canResume").value(false))
                .andExpect(jsonPath("$.attemptsUsed").value(0))
                .andExpect(jsonPath("$.remainingAttempts").value(2))
                .andExpect(jsonPath("$.activeAttemptId").value(nullValue()))
                .andExpect(jsonPath("$.title").value("Public Session"))
                .andExpect(jsonPath("$.examTitle").value("Exam"));
    }

    @Test
    void classRestricted_nonMember_returns403WithoutMetadata() throws Exception {
        mockMvc.perform(get(ENDPOINT, classRestrictedSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_ELIGIBLE"));
    }

    @Test
    void classRestricted_assignedMember_canStart() throws Exception {
        assignClassToSession(classroomId, classRestrictedSessionId);
        addMemberToClassroom(studentProfileId, classroomId);

        mockMvc.perform(get(ENDPOINT, classRestrictedSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CAN_START"))
                .andExpect(jsonPath("$.visibility").value("CLASS_RESTRICTED"));
    }

    @Test
    void classRestricted_memberWithRevokedWorkspaceMembership_returns403() throws Exception {
        assignClassToSession(classroomId, classRestrictedSessionId);
        addMemberToClassroom(studentProfileId, classroomId);
        jdbc.update("UPDATE workspace_memberships SET status = 'REMOVED' "
                + "WHERE user_id = " + studentUserId + " AND workspace_id = "
                + "(SELECT workspace_id FROM exams WHERE id = " + examId + ")");

        mockMvc.perform(get(ENDPOINT, classRestrictedSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_ELIGIBLE"));
    }

    @Test
    void blockedParticipant_returns403() throws Exception {
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, user_id, status, "
                + "added_by, added_at, blocked_at, version) VALUES (" + schoolId + "," + publicSessionId + ","
                + studentUserId + ",'BLOCKED'," + studentUserId + ",now(),now(),0)");

        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_ELIGIBLE"));
    }

    @Test
    void dualRoleTeacherStudent_allowedForPublicSession() throws Exception {
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", studentUserId);

        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CAN_START"));
    }

    // ============================================================
    // Lifecycle / window / quota states (safe actionable UI)
    // ============================================================


    @Test
    void draftSession_deniedWithoutMetadata() throws Exception {
        // Discovery hides DRAFT sessions entirely; the deep-link pre-flight
        // must not leak their title/window to link holders.
        jdbc.update("UPDATE exam_sessions SET status = 'DRAFT', opened_at = NULL WHERE id = " + publicSessionId);

        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_ELIGIBLE"));
    }

    @Test
    void notYetStarted_windowInFuture() throws Exception {
        clock.setInstant(Instant.parse("2026-07-10T06:00:00Z"));
        jdbc.update("UPDATE exam_sessions SET starts_at = '2026-07-10T09:00:00Z' WHERE id = " + publicSessionId);

        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_YET_STARTED"))
                .andExpect(jsonPath("$.canStartNow").value(false));
    }

    @Test
    void closedSession_ended() throws Exception {
        jdbc.update("UPDATE exam_sessions SET status = 'CLOSED', closed_at = now() WHERE id = " + publicSessionId);

        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENDED"))
                .andExpect(jsonPath("$.canStartNow").value(false));
    }

    @Test
    void cancelledSession_ended() throws Exception {
        jdbc.update("UPDATE exam_sessions SET status = 'CANCELLED', opened_at = NULL WHERE id = " + publicSessionId);

        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENDED"));
    }

    @Test
    void windowOver_ended() throws Exception {
        clock.setInstant(Instant.parse("2026-07-10T20:00:00Z"));

        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENDED"));
    }

    @Test
    void attemptsExhausted_noAttemptsLeft() throws Exception {
        // Two prior attempts against max_attempts = 2.
        insertAttempt("SUBMITTED");
        insertAttempt("SUBMITTED");

        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NO_ATTEMPTS_LEFT"))
                .andExpect(jsonPath("$.attemptsUsed").value(2))
                .andExpect(jsonPath("$.remainingAttempts").value(0))
                .andExpect(jsonPath("$.canStartNow").value(false));
    }

    @Test
    void activeAttempt_canResume() throws Exception {
        long attemptId = insertAttempt("IN_PROGRESS");

        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CAN_RESUME"))
                .andExpect(jsonPath("$.canResume").value(true))
                .andExpect(jsonPath("$.activeAttemptId").value((int) attemptId))
                .andExpect(jsonPath("$.canStartNow").value(false));
    }

    @Test
    void expiredActiveAttempt_attemptExpired() throws Exception {
        insertAttempt("IN_PROGRESS");
        // After the attempt deadline (08:00+1h) but still inside the session window (< 10:00Z).
        clock.setInstant(Instant.parse("2026-07-10T09:30:00Z"));

        mockMvc.perform(get(ENDPOINT, publicSessionId).with(jwtFor(studentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ATTEMPT_EXPIRED"))
                .andExpect(jsonPath("$.canResume").value(false))
                .andExpect(jsonPath("$.canStartNow").value(false));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static JwtRequestPostProcessor jwtFor(Long userId) {
        return jwt().jwt(t -> t.subject(userId.toString()).claim("token_version", 0));
    }

    /** One attempt row for the current student against the PUBLIC session. */
    private long insertAttempt(String status) {
        Instant now = Instant.now(clock);
        boolean submitted = "SUBMITTED".equals(status);
        int number = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_number),0)+1 FROM attempts WHERE exam_session_id = ? AND user_id = ?",
                Integer.class, publicSessionId, studentUserId);
        String submittedCols = submitted ? ", submitted_at, submission_idempotency_key" : "";
        String submittedVals = submitted ? ",'" + now + "','sk" + number + "-" + studentUserId + "'" : "";
        return insert("INSERT INTO attempts (school_id, exam_session_id, user_id, student_profile_id, "
                + "attempt_number, status, started_at, deadline_at" + submittedCols + ") VALUES ("
                + schoolId + "," + publicSessionId + "," + studentUserId + "," + studentProfileId
                + "," + number + ",'" + status + "','" + now + "','" + now.plusSeconds(3600) + "'"
                + submittedVals + ")");
    }

    private void assignClassToSession(long classroomId, long sessionId) {
        jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id, school_id) VALUES ("
                + sessionId + "," + classroomId + "," + schoolId + ")");
    }

    private void addMemberToClassroom(long studentProfileId, long classroomId) {
        jdbc.update("INSERT INTO classroom_members (classroom_id, student_profile_id, school_id) VALUES ("
                + classroomId + "," + studentProfileId + "," + schoolId + ")");
        jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                + "SELECT e.workspace_id, sp.user_id, 'MEMBER', 'ACTIVE' "
                + "FROM exams e, student_profiles sp WHERE e.id=" + examId
                + " AND sp.id=" + studentProfileId + " ON CONFLICT DO NOTHING");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
