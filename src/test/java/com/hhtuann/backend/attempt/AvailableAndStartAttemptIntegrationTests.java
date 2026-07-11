package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.AvailableSessionsResponse;
import com.hhtuann.backend.attempt.dto.AvailableSessionsResponse.AvailableSessionItem;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.dto.StartAttemptResponse;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the two A3.2-1 endpoints (available sessions +
 * start/resume)
 * at the service layer. Covers visibility, flags, auth, state checks, deadline,
 * snapshot order, and data-leak safety.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@Transactional
class AvailableAndStartAttemptIntegrationTests {

        @Autowired
        private AttemptService attemptService;
        @Autowired
        private JdbcTemplate jdbc;
        @Autowired
        private MutableClock clock;
        @Autowired
        private EntityManager em;

        private long studentUserId;
        private long schoolId;
        private long studentProfileId;
        private long examVersionId;
        private long sessionId;
        private long subjectId;
        private long teacherId;

        @BeforeEach
        void setUp() {
                Instant baseTime = Instant.parse("2026-07-03T08:00:00Z");
                clock.setInstant(baseTime);

                studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('s1','s1@t.com','h','S1')");
                long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
                schoolId = insert("INSERT INTO schools (code, name) VALUES ('AS','A321 School')");
                long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
                long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) "
                                + "VALUES (" + schoolId + "," + gl + ",'MATH','Math')");
                subjectId = subj;
                long teacher = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                                + "VALUES (" + studentUserId + "," + schoolId + ",'TC')");
                teacherId = teacher;
                studentProfileId = insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                                + "VALUES (" + studentUserId + "," + schoolId + ",'SC')");
                long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) "
                                + "VALUES (" + schoolId + "," + subj + "," + teacher + ",'B','Bank')");
                long q = insert("INSERT INTO questions (question_bank_id, code, created_by) "
                                + "VALUES (" + bank + ",'Q'," + studentUserId + ")");
                long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                                + "default_points, metadata, created_by) VALUES (" + q
                                + ",1,'SINGLE_CHOICE','Question 1',2,'{}'::jsonb,"
                                + studentUserId + ")");
                long q2 = insert("INSERT INTO questions (question_bank_id, code, created_by) "
                                + "VALUES (" + bank + ",'Q2'," + studentUserId + ")");
                long qv2 = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                                + "default_points, metadata, created_by) VALUES (" + q2
                                + ",1,'SINGLE_CHOICE','Question 2',3,'{}'::jsonb,"
                                + studentUserId + ")");
                long examId = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) "
                                + "VALUES (" + schoolId + "," + subj + "," + teacher + ",'E','Exam')");
                examVersionId = insert(
                                "INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, "
                                                + "published_at, created_by) VALUES (" + schoolId + "," + examId
                                                + ",1,'PUBLISHED',5,now(),"
                                                + studentUserId + ")");
                long sec1 = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES ("
                                + examVersionId + ",'S1',0)");
                long sec2 = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES ("
                                + examVersionId + ",'S2',1)");
                long eq1 = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, "
                                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                                + "VALUES (" + examVersionId + "," + sec1 + "," + q + "," + qv
                                + ",'QC1','SINGLE_CHOICE','Question 1',2,0,"
                                + "'{}'::jsonb)");
                long eq2 = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, "
                                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                                + "VALUES (" + examVersionId + "," + sec2 + "," + q2 + "," + qv2
                                + ",'QC2','SINGLE_CHOICE','Question 2',3,0,"
                                + "'{}'::jsonb)");
                jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) "
                                + "VALUES (" + eq1 + ",'A','Opt A',false,0),(" + eq1 + ",'B','Opt B',false,1),(" + eq1
                                + ",'C','Opt C',true,2),(" + eq1 + ",'D','Opt D',false,3)");
                jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) "
                                + "VALUES (" + eq2 + ",'A','Opt C',false,0),(" + eq2 + ",'B','Opt D',false,1),(" + eq2
                                + ",'C','Opt E',true,2),(" + eq2 + ",'D','Opt F',false,3)");
                // Session OPEN with window around baseTime.
                sessionId = insert(
                                "INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, "
                                                + "starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                                                + schoolId + "," + examVersionId + ","
                                                + teacher + ",'S1','Session 1','OPEN','" + baseTime.minusSeconds(3600)
                                                + "','" + baseTime.plusSeconds(7200)
                                                + "',2," + studentUserId + ",'" + baseTime.minusSeconds(3600) + "')");
                insert("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) "
                                + "VALUES (" + schoolId + "," + sessionId + "," + studentProfileId + "," + studentUserId
                                + ")");
        }

        // ============================================================
        // AVAILABLE SESSIONS
        // ============================================================

        @Test
        void availableReturnsEligibleSession() {
                AvailableSessionsResponse resp = attemptService.getAvailableSessions(studentUserId);
                assertThat(resp.items()).hasSize(1);
                AvailableSessionItem item = resp.items().get(0);
                assertThat(item.sessionId()).isEqualTo(sessionId);
                assertThat(item.sessionStatus()).isEqualTo("OPEN");
                assertThat(item.attemptsUsed()).isZero();
                assertThat(item.remainingAttempts()).isEqualTo(2);
                assertThat(item.activeAttemptId()).isNull();
                assertThat(item.canStartNow()).isTrue();
                assertThat(item.canResume()).isFalse();
                assertThat(resp.serverTime()).isNotNull();
        }

        @Test
        void availableHidesClosedSession() {
                jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=now() WHERE id=" + sessionId);
                assertThat(attemptService.getAvailableSessions(studentUserId).items()).isEmpty();
        }

        @Test
        void availableComputesFlagsAfterStart() {
                // Create one IN_PROGRESS attempt.
                attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null));
                AvailableSessionsResponse resp = attemptService.getAvailableSessions(studentUserId);
                assertThat(resp.items()).hasSize(1);
                AvailableSessionItem item = resp.items().get(0);
                assertThat(item.attemptsUsed()).isEqualTo(1);
                assertThat(item.remainingAttempts()).isEqualTo(1);
                assertThat(item.activeAttemptId()).isNotNull();
                assertThat(item.activeAttemptDeadlineAt()).isNotNull();
                assertThat(item.canResume()).isTrue();
                assertThat(item.canStartNow()).isFalse();
        }

        @Test
        void availableNoAnswerLeak() {
                AvailableSessionsResponse resp = attemptService.getAvailableSessions(studentUserId);
                String json = resp.toString();
                assertThat(json).doesNotContain("isCorrect").doesNotContain("answerKey");
        }

        // ============================================================
        // START / RESUME
        // ============================================================

        @Test
        void startCreatesNewAttempt() {
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(resp.resumed()).isFalse();
                assertThat(resp.attemptNumber()).isEqualTo(1);
                assertThat(resp.status()).isEqualTo("IN_PROGRESS");
                assertThat(resp.startedAt()).isNotNull();
                assertThat(resp.deadlineAt()).isNotNull();
                assertThat(resp.maxAttempts()).isEqualTo(2);
                assertThat(resp.questions()).hasSize(2);
        }

        @Test
        void startResumeReturnsSameAttempt() {
                StartAttemptResponse first = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                StartAttemptResponse second = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(second.resumed()).isTrue();
                assertThat(second.attemptId()).isEqualTo(first.attemptId());
                assertThat(second.attemptNumber()).isEqualTo(first.attemptNumber());
                assertThat(second.questions()).hasSize(first.questions().size());
        }

        @Test
        void startRejectsMaxAttemptsReached() {
                // maxAttempts=2. Start + submit attempt 1, start attempt 2 + submit. Attempt 3
                // → rejected.
                StartAttemptResponse a1 = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                submitAttempt(a1.attemptId());
                StartAttemptResponse a2 = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                submitAttempt(a2.attemptId());
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class);
        }

        @Test
        void startRejectsOutsideWindowBeforeStart() {
                // Set startsAt to the future.
                jdbc.update("UPDATE exam_sessions SET starts_at='" + clock.instant().plusSeconds(3600) + "' WHERE id="
                                + sessionId);
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class);
        }

        @Test
        void startDeadlineIsMinOfSessionEndAndDuration() {
                // duration_minutes defaults to 60 in exam_versions. Session ends_at =
                // baseTime+2h.
                // So deadline = min(baseTime+2h, startedAt+1h) = startedAt+1h (duration is
                // shorter).
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(resp.deadlineAt()).isEqualTo(resp.startedAt().plusSeconds(3600));
        }

        @Test
        void startQuestionsOrderedBySectionThenPosition() {
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(resp.questions()).hasSize(2);
                assertThat(resp.questions().get(0).displayOrder()).isZero();
                assertThat(resp.questions().get(1).displayOrder()).isEqualTo(1);
        }

        @Test
        void startNoAnswerLeakInResponse() {
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                String json = resp.toString();
                assertThat(json).doesNotContain("isCorrect").doesNotContain("answerKey").doesNotContain("explanation");
        }

        @Test
        void startDoesNotCreateAnswers() {
                Long attemptId = attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null))
                                .attemptId();
                Integer count = jdbc.queryForObject(
                                "SELECT count(*) FROM attempt_answers WHERE attempt_id = " + attemptId, Integer.class);
                assertThat(count).isZero();
        }

        @Test
        void startRejectsSessionNotFound() {
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, 999999L,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class);
        }

        @Test
        void startRejectsMissingStudentRole() {
                long teacherRole = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
                jdbc.update("UPDATE user_roles SET role_id=" + teacherRole + " WHERE user_id=" + studentUserId);
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class);
        }

        // ============================================================
        // Remediation tests (A3.2-1R)
        // ============================================================

        @Test
        void startPersistsClientInstanceId() {
                java.util.UUID cid = java.util.UUID.randomUUID();
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(cid));
                Long stored = jdbc.queryForObject(
                                "SELECT client_instance_id FROM attempts WHERE id=" + resp.attemptId(),
                                java.util.UUID.class) == null ? null : 1L;
                // verify the UUID was persisted (not null)
                assertThat(stored).isNotNull();
        }

        @Test
        void startOmittedClientInstanceIdIsNull() {
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                Integer count = jdbc.queryForObject(
                                "SELECT count(*) FROM attempts WHERE id=" + resp.attemptId()
                                                + " AND client_instance_id IS NULL",
                                Integer.class);
                assertThat(count).isEqualTo(1);
        }

        @Test
        void availableRejectsInactiveProfile() {
                jdbc.update("UPDATE student_profiles SET enrollment_status='INACTIVE' WHERE id=" + studentProfileId);
                assertThatThrownBy(() -> attemptService.getAvailableSessions(studentUserId))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void startRejectsGraduatedProfile() {
                jdbc.update("UPDATE student_profiles SET enrollment_status='GRADUATED' WHERE id=" + studentProfileId);
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void startRejectsEmptyExam() {
                // Create a session with a version that has no exam_questions.
                long emptyExam = insert(
                                "INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES ("
                                                + schoolId + "," + subjectId + "," + teacherId + ",'EE','Empty')");
                long emptyVersion = insert(
                                "INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, "
                                                + "published_at, created_by) VALUES (" + schoolId + "," + emptyExam
                                                + ",1,'PUBLISHED',10,now(),"
                                                + studentUserId + ")");
                long emptySession = insert(
                                "INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, "
                                                + "status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                                                + schoolId + ","
                                                + emptyVersion + "," + teacherId + ",'ES','ES','OPEN','"
                                                + clock.instant().minusSeconds(3600) + "','"
                                                + clock.instant().plusSeconds(7200) + "',1," + studentUserId + ",'"
                                                + clock.instant().minusSeconds(3600) + "')");
                jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) "
                                + "VALUES (" + schoolId + "," + emptySession + "," + studentProfileId + ","
                                + studentUserId + ")");
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, emptySession,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        }

        @Test
        void startAutoOpensScheduledSessionInWindow() {
                // Lazy-open: SCHEDULED within the time window → auto-transitioned to OPEN on student access.
                jdbc.update("UPDATE exam_sessions SET status='SCHEDULED', opened_at=NULL WHERE id=" + sessionId);
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(resp).isNotNull();
        }

        @Test
        void startRejectsClosedSession() {
                jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=now() WHERE id=" + sessionId);
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_SESSION_NOT_OPEN"));
        }

        @Test
        void availableSCHEDULEDInWindowCanStart() {
                // Lazy-open: SCHEDULED within window → canStartNow is true (auto-opened on start).
                jdbc.update("UPDATE exam_sessions SET status='SCHEDULED', opened_at=NULL WHERE id=" + sessionId);
                AvailableSessionsResponse resp = attemptService.getAvailableSessions(studentUserId);
                assertThat(resp.items()).hasSize(1);
                assertThat(resp.items().get(0).canStartNow()).isTrue();
                assertThat(resp.items().get(0).sessionStatus()).isEqualTo("SCHEDULED");
        }

        @Test
        void availableExpiredActiveRetainsIdWithFlagsFalse() {
                // Start an attempt, then advance clock past deadline.
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                clock.setInstant(resp.deadlineAt().plusSeconds(60));
                AvailableSessionsResponse available = attemptService.getAvailableSessions(studentUserId);
                AvailableSessionItem item = available.items().get(0);
                assertThat(item.activeAttemptId()).isEqualTo(resp.attemptId());
                assertThat(item.activeAttemptDeadlineAt()).isNotNull();
                assertThat(item.canResume()).isFalse();
                assertThat(item.canStartNow()).isFalse();
        }

        @Test
        void startDeadlineCappedBySessionEnd() {
                // Set session ends_at close to now; duration is 60min (default).
                // deadline = min(endsAt, startedAt+60min). If endsAt < startedAt+60min,
                // deadline=endsAt.
                jdbc.update("UPDATE exam_versions SET duration_minutes=120 WHERE id=" + examVersionId);
                // duration=120min but session ends in 30min → deadline=endsAt.
                Instant nearEnd = clock.instant().plusSeconds(1800);
                jdbc.update("UPDATE exam_sessions SET ends_at='" + nearEnd + "' WHERE id=" + sessionId);
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(resp.deadlineAt()).isEqualTo(nearEnd);
        }

        // ============================================================
        // R4: Missing service boundary tests
        // ============================================================

        @Test
        void startAcceptsExactStartsAt() {
                jdbc.update("UPDATE exam_sessions SET starts_at='" + clock.instant() + "' WHERE id=" + sessionId);
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(resp.resumed()).isFalse();
        }

        @Test
        void startAcceptsExactEndsAt() {
                jdbc.update("UPDATE exam_sessions SET ends_at='" + clock.instant() + "' WHERE id=" + sessionId);
                StartAttemptResponse resp = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(resp.resumed()).isFalse();
        }

        @Test
        void startRejectsAfterEndsAt() {
                jdbc.update("UPDATE exam_sessions SET ends_at='" + clock.instant().minusSeconds(1) + "' WHERE id="
                                + sessionId);
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_OUTSIDE_WINDOW"));
        }

        @Test
        void startRejectsWithdrawnProfile() {
                jdbc.update("UPDATE student_profiles SET enrollment_status='WITHDRAWN' WHERE id=" + studentProfileId);
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void startResumeDoesNotOverwriteClientInstanceId() {
                java.util.UUID original = java.util.UUID.randomUUID();
                StartAttemptResponse first = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(original));
                // Resume with a different UUID — original must be preserved.
                attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(java.util.UUID.randomUUID()));
                java.util.UUID stored = jdbc.queryForObject(
                                "SELECT client_instance_id FROM attempts WHERE id=" + first.attemptId(),
                                java.util.UUID.class);
                assertThat(stored).isEqualTo(original);
        }

        @Test
        void startMaxPlusOneAfterMultipleSubmitted() {
                // maxAttempts=2: start+submit #1, start+submit #2, then #3 must fail.
                submitAttempt(attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null))
                                .attemptId());
                submitAttempt(attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null))
                                .attemptId());
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_MAX_REACHED"));
        }

        @Test
        void availableDeterministicStartsAtThenSessionIdOrder() {
                // Create a second session with the same startsAt.
                long session2 = insert(
                                "INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, "
                                                + "starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                                                + schoolId + "," + examVersionId + ","
                                                + teacherId + ",'S2','S2','OPEN','" + clock.instant().minusSeconds(3600)
                                                + "','"
                                                + clock.instant().plusSeconds(7200) + "',1," + studentUserId + ",'"
                                                + clock.instant().minusSeconds(3600) + "')");
                insert("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) "
                                + "VALUES (" + schoolId + "," + session2 + "," + studentProfileId + "," + studentUserId
                                + ")");
                AvailableSessionsResponse resp = attemptService.getAvailableSessions(studentUserId);
                assertThat(resp.items()).hasSize(2);
                // Both have same startsAt; order by sessionId ASC.
                assertThat(resp.items().get(0).sessionId()).isLessThan(resp.items().get(1).sessionId());
                assertThat(resp.items().get(0).startsAt()).isEqualTo(resp.items().get(1).startsAt());
        }

        @Test
        void startZeroAttemptAnswersCreated() {
                Long attemptId = attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null))
                                .attemptId();
                Integer count = jdbc.queryForObject(
                                "SELECT count(*) FROM attempt_answers WHERE attempt_id = " + attemptId, Integer.class);
                assertThat(count).isZero();
        }

        @Test
        void availableDRAFTHidden() {
                jdbc.update("UPDATE exam_sessions SET status='DRAFT', opened_at=NULL WHERE id=" + sessionId);
                assertThat(attemptService.getAvailableSessions(studentUserId).items()).isEmpty();
        }

        @Test
        void availableCANCELLEDHidden() {
                jdbc.update("UPDATE exam_sessions SET status='CANCELLED', opened_at=NULL, closed_at=NULL WHERE id="
                                + sessionId);
                assertThat(attemptService.getAvailableSessions(studentUserId).items()).isEmpty();
        }

        @Test
        void availableQuotaZeroRemaining() {
                // maxAttempts=1, use one attempt → remaining=0, canStartNow=false.
                jdbc.update("UPDATE exam_sessions SET max_attempts=1 WHERE id=" + sessionId);
                submitAttempt(attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null))
                                .attemptId());
                AvailableSessionsResponse resp = attemptService.getAvailableSessions(studentUserId);
                assertThat(resp.items().get(0).remainingAttempts()).isZero();
                assertThat(resp.items().get(0).canStartNow()).isFalse();
        }

        // ============================================================
        // R7: option-order snapshot validation (persisted order is authoritative)
        // ============================================================

        @Test
        void startOptionOrderFollowsPersistedArrayNotSourcePosition() {
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                Long aqId = aqIdForQc1(r.attemptId());
                // Mutate the persisted snapshot to [D,C,B,A]; resume must render that order
                // regardless of source position.
                jdbc.update("UPDATE attempt_questions SET option_order='[\"D\",\"C\",\"B\",\"A\"]'::jsonb WHERE id="
                                + aqId);
                em.clear(); // drop cached snapshot so resume re-reads the mutated persisted order
                StartAttemptResponse resume = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(resume.questions().get(0).options()).extracting(o -> o.optionKey()).containsExactly("D", "C",
                                "B", "A");
        }

        @Test
        void resumeKeepsPersistedOptionOrderAfterSourcePositionMutation() {
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(r.questions().get(0).options()).extracting(o -> o.optionKey()).containsExactly("A", "B", "C",
                                "D");
                Long eq1 = jdbc.queryForObject(
                                "SELECT id FROM exam_questions WHERE question_code='QC1' AND exam_version_id="
                                                + examVersionId,
                                Long.class);
                // Reverse all 4 source positions after start via a two-step shift (avoids a
                // transient
                // uk_exam_options_question_position violation from a direct swap); the
                // persisted snapshot must win on resume.
                jdbc.update("UPDATE exam_question_options SET position = position + 100 WHERE exam_question_id=" + eq1);
                jdbc.update("UPDATE exam_question_options SET position = CASE option_key WHEN 'A' THEN 3 WHEN 'B' THEN 2 WHEN 'C' THEN 1 WHEN 'D' THEN 0 END WHERE exam_question_id="
                                + eq1);
                em.clear();
                StartAttemptResponse resume = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                assertThat(resume.questions().get(0).options()).extracting(o -> o.optionKey()).containsExactly("A", "B",
                                "C", "D");
        }

        @Test
        void resumeRejectsNullOptionOrderForOptionBasedQuestion() {
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                jdbc.update("UPDATE attempt_questions SET option_order=NULL WHERE id=" + aqIdForQc1(r.attemptId()));
                em.clear();
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        }

        @Test
        void resumeRejectsEmptyOptionOrder() {
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                jdbc.update("UPDATE attempt_questions SET option_order='[]'::jsonb WHERE id="
                                + aqIdForQc1(r.attemptId()));
                em.clear();
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        }

        @Test
        void resumeRejectsDuplicateOptionKey() {
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                jdbc.update("UPDATE attempt_questions SET option_order='[\"A\",\"A\"]'::jsonb WHERE id="
                                + aqIdForQc1(r.attemptId()));
                em.clear();
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        }

        @Test
        void resumeRejectsUnknownOptionKey() {
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                jdbc.update("UPDATE attempt_questions SET option_order='[\"A\",\"Z\"]'::jsonb WHERE id="
                                + aqIdForQc1(r.attemptId()));
                em.clear();
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        }

        @Test
        void resumeRejectsNonStringOptionKey() {
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                jdbc.update("UPDATE attempt_questions SET option_order='[5]'::jsonb WHERE id="
                                + aqIdForQc1(r.attemptId()));
                em.clear();
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        }

        @Test
        void resumeRejectsOptionCountMismatch() {
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null));
                // Source has 2 options; persisted order lists only 1.
                jdbc.update("UPDATE attempt_questions SET option_order='[\"A\"]'::jsonb WHERE id="
                                + aqIdForQc1(r.attemptId()));
                em.clear();
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        }

        @Test
        void numericFillAcceptsNullOptionOrder() {
                long nfSession = createNumericFillSession();
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, nfSession,
                                new StartAttemptRequest(null));
                assertThat(r.resumed()).isFalse();
                assertThat(r.questions()).hasSize(1);
                assertThat(r.questions().get(0).options()).isEmpty();
                assertThat(r.questions().get(0).questionType()).isEqualTo("NUMERIC_FILL");
        }

        @Test
        void numericFillRejectsNonNullOptionOrder() {
                long nfSession = createNumericFillSession();
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, nfSession,
                                new StartAttemptRequest(null));
                jdbc.update("UPDATE attempt_questions SET option_order='[\"A\"]'::jsonb WHERE attempt_id="
                                + r.attemptId());
                em.clear();
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, nfSession,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        }

        @Test
        void resumeRejectsOptionBasedQuestionWhenSourceOptionsDeleted() {
                // R8: resume validates the CURRENT immutable source options. Deleting all
                // source options
                // after start drops the current count to 0 while the denormalized type is still
                // SINGLE_CHOICE → mismatch.
                attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null));
                Long eq1 = jdbc.queryForObject(
                                "SELECT id FROM exam_questions WHERE question_code='QC1' AND exam_version_id="
                                                + examVersionId,
                                Long.class);
                jdbc.update("DELETE FROM exam_question_options WHERE exam_question_id=" + eq1);
                em.clear();
                assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, sessionId,
                                new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        }

        // ============================================================
        // R7: school-scope + ordering edge cases
        // ============================================================

        @Test
        void startAcceptsSchoolIdAboveLongByteCacheRange() {
                // School ID > 127 (outside the Long cache -128..127) so a naive boxed-Long !=
                // comparison
                // would falsely mismatch. Objects.equals must accept the same-school start.
                long bigSchool = insert("INSERT INTO schools (id, code, name) VALUES (9999, 'BIG','BigSchool')");
                assertThat(bigSchool).isGreaterThan(127);
                long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('bg','bg@t.com','h','BG')");
                long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
                long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u + ","
                                + bigSchool + ",'TC')");
                long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u + ","
                                + bigSchool + ",'SC')");
                // Subject must live in the same school (composite same-school FK on
                // question_banks/exams).
                long gl = insert(
                                "INSERT INTO grade_levels (school_id, code, name) VALUES (" + bigSchool + ",'GL','G')");
                long bigSubject = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES ("
                                + bigSchool + "," + gl + ",'M','Math')");
                long ver = createVersionInSchool(bigSchool, bigSubject, tp, u, "SINGLE_CHOICE");
                long session = insert(
                                "INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, "
                                                + "starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                                                + bigSchool + "," + ver + "," + tp
                                                + ",'BS','Big','OPEN','" + clock.instant().minusSeconds(3600) + "','"
                                                + clock.instant().plusSeconds(7200)
                                                + "',2," + u + ",'" + clock.instant().minusSeconds(3600) + "')");
                insert("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) "
                                + "VALUES (" + bigSchool + "," + session + "," + sp + "," + u + ")");
                StartAttemptResponse r = attemptService.startAttempt(u, session, new StartAttemptRequest(null));
                assertThat(r.resumed()).isFalse();
                assertThat(r.status()).isEqualTo("IN_PROGRESS");
        }

        @Test
        void startRejectsCrossSchoolProfileMismatch() {
                // A student from a DIFFERENT school must be denied — visibility check requires
                // student.schoolId == session.schoolId, even when the session is PUBLIC.
                long otherSchool = insert("INSERT INTO schools (code, name) VALUES ('OS','Other School')");
                long u2 = insert(
                                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('os','os@t.com','h','OS')");
                long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u2 + "," + roleId + ")");
                insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u2 + ","
                                + otherSchool + ",'OS')");
                assertThatThrownBy(() -> attemptService.startAttempt(u2, sessionId, new StartAttemptRequest(null)))
                                .isInstanceOf(AttemptException.class)
                                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code())
                                                .isEqualTo("ATTEMPT_NOT_ELIGIBLE"));
        }

        @Test
        void startOrdersQuestionsBySectionThenPositionThenId() {
                // A single section with questions inserted at positions [2,0,1] (ids
                // ascending). The snapshot
                // ORDER BY is (section.position, question.position, exam_question.id). Distinct
                // positions are
                // forced by V8 uk_exam_questions_section_position, so id is the deterministic
                // final tiebreaker
                // (an exact position tie is impossible under V8 and therefore not exercised
                // here).
                long ver = createBareVersion(schoolId, teacherId, studentUserId);
                long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver
                                + ",'S',0)");
                long[] eqs = new long[3];
                int[] positions = { 2, 0, 1 };
                for (int i = 0; i < 3; i++) {
                        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) "
                                        + "VALUES ((SELECT id FROM question_banks WHERE school_id=" + schoolId
                                        + " LIMIT 1),'ORD" + i + "'," + studentUserId + ")");
                        long qv = insert(
                                        "INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) "
                                                        + "VALUES (" + q + ",1,'SINGLE_CHOICE','Q" + i
                                                        + "',1,'{}'::jsonb," + studentUserId + ")");
                        eqs[i] = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, "
                                        + "question_code, question_type, content, default_points, position, metadata) VALUES ("
                                        + ver + "," + sec + "," + q + "," + qv
                                        + ",'OC" + i + "','SINGLE_CHOICE','Q" + i + "',1," + positions[i]
                                        + ",'{}'::jsonb)");
                        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) "
                                        + "VALUES (" + eqs[i] + ",'A','a',false,0),(" + eqs[i] + ",'B','b',false,1),("
                                        + eqs[i] + ",'C','c',true,2),(" + eqs[i] + ",'D','d',false,3)");
                }
                long session = insert(
                                "INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, "
                                                + "starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                                                + schoolId + "," + ver + "," + teacherId
                                                + ",'ORD','Ord','OPEN','" + clock.instant().minusSeconds(3600) + "','"
                                                + clock.instant().plusSeconds(7200)
                                                + "',2," + studentUserId + ",'" + clock.instant().minusSeconds(3600)
                                                + "')");
                insert("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) "
                                + "VALUES (" + schoolId + "," + session + "," + studentProfileId + "," + studentUserId
                                + ")");
                StartAttemptResponse r = attemptService.startAttempt(studentUserId, session,
                                new StartAttemptRequest(null));
                // eqs[0]=pos2, eqs[1]=pos0, eqs[2]=pos1 → snapshot order by position: eqs[1],
                // eqs[2], eqs[0].
                assertThat(r.questions()).extracting(qv -> qv.examQuestionId()).containsExactly(eqs[1], eqs[2], eqs[0]);
                assertThat(r.questions()).extracting(qv -> qv.displayOrder()).containsExactly(0, 1, 2);
        }

        // ============================================================
        // Helpers
        // ============================================================

        private Long aqIdForQc1(Long attemptId) {
                return jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attemptId
                                + " AND exam_question_id=(SELECT id FROM exam_questions WHERE question_code='QC1' AND exam_version_id="
                                + examVersionId + ")", Long.class);
        }

        private long createNumericFillSession() {
                long ver = createVersionInSchool(schoolId, subjectId, teacherId, studentUserId, "NUMERIC_FILL");
                long session = insert(
                                "INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, "
                                                + "starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                                                + schoolId + "," + ver + "," + teacherId
                                                + ",'NF','NumFill','OPEN','" + clock.instant().minusSeconds(3600)
                                                + "','" + clock.instant().plusSeconds(7200)
                                                + "',2," + studentUserId + ",'" + clock.instant().minusSeconds(3600)
                                                + "')");
                insert("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) "
                                + "VALUES (" + schoolId + "," + session + "," + studentProfileId + "," + studentUserId
                                + ")");
                return session;
        }

        /**
         * Creates a PUBLISHED exam version with one section + one exam_question of the
         * given type (no options).
         */
        private long createVersionInSchool(long school, long subject, long teacher, long user, String questionType) {
                long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) "
                                + "VALUES (" + school + "," + subject + "," + teacher + ",'B" + school + questionType
                                + "','Bank')");
                long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q"
                                + questionType + "'," + user + ")");
                // NUMERIC_FILL requires a structured answer_key on BOTH question_versions (V7)
                // and exam_questions (V8);
                // other types must have answer_key NULL (chk_*_answer_presence).
                String answerKey = "NUMERIC_FILL".equals(questionType)
                                ? "'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"two decimals\"}'::jsonb"
                                : "NULL";
                long qv = insert(
                                "INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, answer_key, created_by) "
                                                + "VALUES (" + q + ",1,'" + questionType + "','NFQ',1,'{}'::jsonb,"
                                                + answerKey + "," + user + ")");
                long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES ("
                                + school + "," + subject + "," + teacher + ",'E" + questionType + "','Exam')");
                long ver = insert(
                                "INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES ("
                                                + school + "," + exam + ",1,'PUBLISHED',1,now()," + user + ")");
                long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver
                                + ",'S',0)");
                long eq = insert(
                                "INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, "
                                                + "question_code, question_type, content, default_points, position, metadata, answer_key) VALUES ("
                                                + ver + "," + sec + "," + q + "," + qv
                                                + ",'QC','" + questionType + "','NFQ',1,0,'{}'::jsonb," + answerKey
                                                + ")");
                // Option-based types require 4 source options
                // (SINGLE_CHOICE/MULTIPLE_CHOICE/TRUE_FALSE_MATRIX → A–D).
                if (!"NUMERIC_FILL".equals(questionType)) {
                        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) "
                                        + "VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq
                                        + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
                }
                return ver;
        }

        /**
         * Creates a bare PUBLISHED exam version (no sections/questions) for ordering
         * tests that add their own.
         */
        private long createBareVersion(long school, long teacher, long user) {
                long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES ("
                                + school + "," + subjectId + "," + teacher + ",'ORD','Ord')");
                return insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES ("
                                + school + "," + exam + ",1,'PUBLISHED',3,now()," + user + ")");
        }

        // ============================================================
        // Helpers
        // ============================================================

        private long insert(String sql) {
                return jdbc.queryForObject(sql + " RETURNING id", Long.class);
        }

        private void submitAttempt(Long attemptId) {
                jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), submission_idempotency_key='K"
                                + attemptId + "' WHERE id=" + attemptId);
        }
}
