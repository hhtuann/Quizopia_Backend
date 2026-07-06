package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptQueryService;
import com.hhtuann.backend.attempt.dto.MyAttemptsResponse;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
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
 * Service-layer tests for {@code GET /api/attempts/my} (A3.2-2): ownership scoping, all statuses,
 * session-state independence, pagination/clamping, deterministic ordering, and no leak.
 *
 * <p>Multiple attempts per (session, student) use SUBMITTED/GRADED (the partial unique index allows
 * only one IN_PROGRESS), each with a distinct attempt_number.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
@SuppressWarnings({"null"})
class MyAttemptsServiceIntegrationTests {

    @Autowired private AttemptQueryService queryService;
    @Autowired private JdbcTemplate jdbc;

    private long studentUserId;
    private long studentProfileId;
    private long schoolId;
    private long sessionId;
    private long examVersionId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('m','m@t.com','h','M')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('MS','My School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'M','Math')");
        long teacher = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + studentUserId + "," + schoolId + ",'TC')");
        studentProfileId = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentUserId + "," + schoolId + ",'SC')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subj + "," + teacher + ",'B','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + studentUserId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + studentUserId + ")");
        long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + schoolId + "," + subj + "," + teacher + ",'E','Exam')");
        examVersionId = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + schoolId + "," + exam + ",1,'PUBLISHED',1,now()," + studentUserId + ")");
        long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + examVersionId + ",'S',0)");
        long eq = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + examVersionId + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        sessionId = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, "
                + "starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + schoolId + "," + examVersionId + "," + teacher + ",'S','Sess','OPEN','" + now.minusSeconds(3600) + "','"
                + now.plusSeconds(7200) + "',5," + studentUserId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + schoolId + "," + sessionId + "," + studentProfileId + "," + studentUserId + ")");
    }

    @Test
    void myReturnsOnlyOwnAttempts() {
        long own = newAttempt("SUBMITTED", "K1", "2026-07-03T10:00:00Z");
        insertOtherStudentAttempt();
        MyAttemptsResponse r = queryService.getMyAttempts(studentUserId, 0, 20);
        assertThat(r.items()).extracting(MyAttemptsResponse.MyAttemptListItem::attemptId).containsExactly(own);
    }

    @Test
    void myHidesForeignStudentAttempts() {
        newAttempt("SUBMITTED", "K1", "2026-07-03T10:00:00Z");
        insertOtherStudentAttempt();
        assertThat(queryService.getMyAttempts(studentUserId, 0, 20).items()).hasSize(1);
    }

    @Test
    void myHidesCrossSchoolAttempts() {
        newAttempt("SUBMITTED", "K1", "2026-07-03T10:00:00Z");
        insertCrossSchoolAttempt();
        assertThat(queryService.getMyAttempts(studentUserId, 0, 20).items()).hasSize(1);
    }

    @Test
    void myIncludesAllStatuses() {
        newAttempt("IN_PROGRESS", null, "2026-07-03T10:00:00Z");
        newAttempt("SUBMITTED", "KS", "2026-07-03T11:00:00Z");
        newAttempt("GRADED", "KG", "2026-07-03T12:00:00Z");
        assertThat(queryService.getMyAttempts(studentUserId, 0, 20).items()).hasSize(3);
    }

    @Test
    void myShowsHistoryWhenSessionClosedOrCancelled() {
        newAttempt("IN_PROGRESS", null, "2026-07-03T10:00:00Z");
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=now() WHERE id=" + sessionId);
        assertThat(queryService.getMyAttempts(studentUserId, 0, 20).items()).hasSize(1);
        jdbc.update("UPDATE exam_sessions SET status='CANCELLED', opened_at=NULL, closed_at=NULL WHERE id=" + sessionId);
        assertThat(queryService.getMyAttempts(studentUserId, 0, 20).items()).hasSize(1);
    }

    @Test
    void myShowsHistoryWhenParticipantBlocked() {
        newAttempt("IN_PROGRESS", null, "2026-07-03T10:00:00Z");
        jdbc.update("UPDATE exam_session_participants SET status='BLOCKED', blocked_at=now() WHERE exam_session_id=" + sessionId);
        assertThat(queryService.getMyAttempts(studentUserId, 0, 20).items()).hasSize(1);
    }

    @Test
    void myEmptyPageIsClean() {
        MyAttemptsResponse r = queryService.getMyAttempts(studentUserId, 0, 20);
        assertThat(r.items()).isEmpty();
        assertThat(r.totalElements()).isZero();
        assertThat(r.totalPages()).isZero();
    }

    @Test
    void myDefaultPageAndSize() {
        newAttempt("SUBMITTED", "K1", "2026-07-03T10:00:00Z");
        MyAttemptsResponse r = queryService.getMyAttempts(studentUserId, 0, 20);
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
    }

    @Test
    void mySizeClampsTo100() {
        assertThat(queryService.getMyAttempts(studentUserId, 0, 500).size()).isEqualTo(100);
    }

    @Test
    void myRejectsNegativePage() {
        assertThatThrownBy(() -> queryService.getMyAttempts(studentUserId, -1, 20))
                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code()).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
    }

    @Test
    void myRejectsZeroSize() {
        assertThatThrownBy(() -> queryService.getMyAttempts(studentUserId, 0, 0))
                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code()).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
    }

    @Test
    void myOrdersByCreatedAtDescThenIdDesc() {
        long a1 = newAttempt("SUBMITTED", "K1", "2026-07-03T10:00:00Z");
        long a2 = newAttempt("SUBMITTED", "K2", "2026-07-03T12:00:00Z");
        long a3 = newAttempt("SUBMITTED", "K3", "2026-07-03T11:00:00Z");
        assertThat(queryService.getMyAttempts(studentUserId, 0, 20).items())
                .extracting(MyAttemptsResponse.MyAttemptListItem::attemptId).containsExactly(a2, a3, a1);
    }

    @Test
    void mySameCreatedAtTieBreaksByIdDesc() {
        long a1 = newAttempt("SUBMITTED", "K1", "2026-07-03T10:00:00Z");
        long a2 = newAttempt("SUBMITTED", "K2", "2026-07-03T10:00:00Z");
        long a3 = newAttempt("SUBMITTED", "K3", "2026-07-03T10:00:00Z");
        assertThat(queryService.getMyAttempts(studentUserId, 0, 20).items())
                .extracting(MyAttemptsResponse.MyAttemptListItem::attemptId).containsExactly(a3, a2, a1);
    }

    @Test
    void myPaginationTotalsCorrect() {
        for (int i = 0; i < 5; i++) {
            newAttempt("SUBMITTED", "K" + i, "2026-07-03T10:0" + i + ":00Z");
        }
        MyAttemptsResponse page0 = queryService.getMyAttempts(studentUserId, 0, 2);
        MyAttemptsResponse page1 = queryService.getMyAttempts(studentUserId, 1, 2);
        MyAttemptsResponse page2 = queryService.getMyAttempts(studentUserId, 2, 2);
        assertThat(page0.items()).hasSize(2);
        assertThat(page1.items()).hasSize(2);
        assertThat(page2.items()).hasSize(1);
        assertThat(page0.totalElements()).isEqualTo(5);
        assertThat(page0.totalPages()).isEqualTo(3);
        assertThat(page0.sort()).isEqualTo("createdAt: DESC");
    }

    @Test
    void myReturnsSessionCodeAndTitle() {
        newAttempt("SUBMITTED", "K1", "2026-07-03T10:00:00Z");
        MyAttemptsResponse.MyAttemptListItem item = queryService.getMyAttempts(studentUserId, 0, 20).items().get(0);
        assertThat(item.sessionId()).isEqualTo(sessionId);
        assertThat(item.sessionCode()).isEqualTo("S");
        assertThat(item.sessionTitle()).isEqualTo("Sess");
    }

    @Test
    void myDoesNotLeakAnswersOrScoreOrGrade() {
        newAttempt("SUBMITTED", "K1", "2026-07-03T10:00:00Z");
        String json = queryService.getMyAttempts(studentUserId, 0, 20).toString();
        assertThat(json).doesNotContain("answerPayload").doesNotContain("questions")
                .doesNotContain("score").doesNotContain("grade").doesNotContain("answerKey")
                .doesNotContain("submissionIdempotencyKey").doesNotContain("studentProfileId");
    }

    @Test
    void myRevokedAttemptReadReturns403() {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') "
                + "AND permission_id=(SELECT id FROM permissions WHERE code='ATTEMPT_READ')");
        assertThatThrownBy(() -> queryService.getMyAttempts(studentUserId, 0, 20))
                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code()).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test
    void myMissingProfileReturnsCorrectCode() {
        long noProfile = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('mp','mp@t.com','h','MP')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + noProfile + "," + roleId + ")");
        assertThatThrownBy(() -> queryService.getMyAttempts(noProfile, 0, 20))
                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code()).isEqualTo("ATTEMPT_STUDENT_PROFILE_NOT_FOUND"));
    }

    @Test
    void myInactiveProfileReturns403() {
        jdbc.update("UPDATE student_profiles SET enrollment_status='INACTIVE' WHERE id=" + studentProfileId);
        assertThatThrownBy(() -> queryService.getMyAttempts(studentUserId, 0, 20))
                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code()).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private int nextAttemptNumber() {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_number),0)+1 FROM attempts WHERE exam_session_id=? AND student_profile_id=?",
                Integer.class, sessionId, studentProfileId);
        return max;
    }

    /** Inserts an own attempt with a controlled created_at; returns its id. */
    private long newAttempt(String status, String key, String createdAtIso) {
        boolean submitted = "SUBMITTED".equals(status) || "GRADED".equals(status);
        String cols = "school_id, exam_session_id, student_profile_id, exam_version_id, attempt_number, "
                + "status, started_at, deadline_at, created_at"
                + (submitted ? ", submitted_at, submission_idempotency_key" : "");
        String submitKey = (key == null) ? "K" + nextAttemptNumber() : key;
        String vals = schoolId + "," + sessionId + "," + studentProfileId + "," + examVersionId + ","
                + nextAttemptNumber() + ",'" + status + "','" + createdAtIso + "'::timestamptz,'"
                + createdAtIso + "'::timestamptz + interval '1 hour','" + createdAtIso + "'::timestamptz"
                + (submitted ? ",'" + createdAtIso + "'::timestamptz,'" + submitKey + "'" : "");
        return insert("INSERT INTO attempts (" + cols + ") VALUES (" + vals + ")");
    }

    private void insertOtherStudentAttempt() {
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o','o@t.com','h','O')");
        long sp2 = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u2 + "," + schoolId + ",'OS')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) "
                + "VALUES (" + schoolId + "," + sessionId + "," + sp2 + "," + studentUserId + ") ON CONFLICT DO NOTHING");
        // Different student → IN_PROGRESS is fine (no 1-active conflict with the caller). 8 columns, 8 values.
        jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, exam_version_id, attempt_number, "
                + "status, started_at, deadline_at) VALUES (" + schoolId + "," + sessionId + "," + sp2 + "," + examVersionId
                + ",1,'IN_PROGRESS', now(), now()+interval '1 hour')");
    }

    private void insertCrossSchoolAttempt() {
        long s2 = insert("INSERT INTO schools (code, name) VALUES ('XS','XS')");
        long gl2 = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + s2 + ",'GL','G')");
        long subj2 = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + s2 + "," + gl2 + ",'M2','M2')");
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('x','x@t.com','h','X')");
        long tp2 = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u2 + "," + s2 + ",'TX')");
        long sp2 = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u2 + "," + s2 + ",'XS')");
        long bank2 = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + s2 + "," + subj2 + "," + tp2 + ",'B2','B2')");
        long q2 = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank2 + ",'Q2'," + u2 + ")");
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q2 + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + u2 + ")");
        long exam2 = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + s2 + "," + subj2 + "," + tp2 + ",'E2','E2')");
        long ver2 = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + s2 + "," + exam2 + ",1,'PUBLISHED',1,now()," + u2 + ")");
        long sess2 = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, created_by, opened_at) VALUES (" + s2 + "," + ver2 + "," + tp2 + ",'S2','S2','OPEN',now(),now()+interval '1 hour'," + u2 + ",now())");
        jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, exam_version_id, attempt_number, "
                + "status, started_at, deadline_at) VALUES (" + s2 + "," + sess2 + "," + sp2 + "," + ver2 + ",1,'IN_PROGRESS',now(),now()+interval '1 hour')");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
