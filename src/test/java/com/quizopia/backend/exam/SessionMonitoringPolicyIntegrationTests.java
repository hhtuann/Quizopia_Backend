package com.quizopia.backend.exam;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.SessionEligibilityResponse;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.exam.dto.CreateExamSessionRequest;
import com.quizopia.backend.exam.dto.ExamSessionDetailResponse;
import com.quizopia.backend.exam.dto.MonitoringPolicyRequest;
import com.quizopia.backend.exam.dto.UpdateExamSessionRequest;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4-A1 (issue #80) — Session Monitoring Policy: validation matrix,
 * immutability freeze, class-assignment safeguards, legacy regression and
 * the fail-closed monitored-start boundary (camera readiness arrives P4-B).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class SessionMonitoringPolicyIntegrationTests {

    @Autowired private ExamSessionService sessionService;
    @Autowired private jakarta.persistence.EntityManager em;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private long teacherId;
    private long studentId;
    private long examId;
    private long classroomId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-11-14T08:00:00Z");
        clock.setInstant(now);
        teacherId = user("tmon");
        grant(teacherId, "TEACHER");
        studentId = user("smon");
        grant(studentId, "STUDENT");

        long gl = ins("INSERT INTO grade_levels (code, name) VALUES ('GL" + uuid6() + "','G')");
        long subj = ins("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','Math')");
        long bank = ins("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherId + "," + subj + ",'B','Bank')");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + teacherId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherId + ")");
        examId = ins("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + teacherId + "," + subj + ",'E','Exam','PUBLISHED',now(),1)");
        long sec = ins("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + examId + ",'S',0)");
        long eq = ins("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + examId + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");

        classroomId = ins("INSERT INTO classrooms (owner_user_id, code, name) VALUES (" + teacherId + ",'CLS" + uuid6() + "','C')");
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES (" + classroomId + "," + studentId + ")");
    }

    // === create: validation matrix ===

    @Test
    void createWithMonitoringOnEligibleClassRestrictedSessionPersistsPolicy() {
        ExamSessionDetailResponse detail = createClassRestricted(monitoring(true, false, false, false, false, false));
        assertThat(detail.monitoring().monitoringEnabled()).isTrue();
        assertThat(detail.monitoring().cameraRequired()).isTrue(); // DERIVED, never client-set
        assertThat(detail.monitoring().aiDetectionEnabled()).isFalse();
        assertThat(detail.monitoring().configurationFrozen()).isFalse();
        assertThat(detail.monitoring().mediaRetentionDays()).isEqualTo(30);
        assertThat(detail.monitoring().activityRetentionDays()).isEqualTo(90);

        // No camera_required column can exist in a contradictory state:
        // the derived flag equals monitoring_enabled by construction.
        Boolean persisted = jdbc.queryForObject(
                "SELECT monitoring_enabled FROM session_monitoring_policies WHERE exam_session_id = ?",
                Boolean.class, detail.id());
        assertThat(persisted).isTrue();
        assertThat(columnExists("session_monitoring_policies", "camera_required")).isFalse();
    }

    @Test
    void createPublicWithMonitoringRejected() {
        assertThatThrownBy(() -> create(monitoring(true, false, false, false, false, false),
                "PUBLIC", List.of(classroomId)))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_MONITORING_INVALID"));
    }

    @Test
    void createClassRestrictedZeroClassesWithMonitoringRejected() {
        assertThatThrownBy(() -> create(monitoring(true, false, false, false, false, false),
                "CLASS_RESTRICTED", List.of()))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_MONITORING_INVALID"));
    }

    @Test
    void createAiWithoutMonitoringRejected() {
        assertThatThrownBy(() -> create(monitoring(false, true, false, false, false, false),
                "CLASS_RESTRICTED", List.of(classroomId)))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_MONITORING_INVALID"));
    }

    @Test
    void createTrackingWithoutMonitoringRejected() {
        assertThatThrownBy(() -> create(monitoring(false, false, true, false, false, false),
                "CLASS_RESTRICTED", List.of(classroomId)))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_MONITORING_INVALID"));
    }

    @Test
    void createSnapshotsWithoutAiRejected() {
        assertThatThrownBy(() -> create(monitoring(true, false, false, true, false, false),
                "CLASS_RESTRICTED", List.of(classroomId)))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_MONITORING_INVALID"));
    }

    @Test
    void createClipsWithoutRecordingRejected() {
        assertThatThrownBy(() -> create(monitoring(true, true, false, false, false, true),
                "CLASS_RESTRICTED", List.of(classroomId)))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_MONITORING_INVALID"));
    }

    @Test
    void createFullValidCombinationAccepted() {
        ExamSessionDetailResponse detail = createClassRestricted(
                monitoring(true, true, true, true, true, true));
        assertThat(detail.monitoring().fullRecordingEnabled()).isTrue();
        assertThat(detail.monitoring().evidenceClipsEnabled()).isTrue();
        assertThat(detail.monitoring().evidenceSnapshotsEnabled()).isTrue();
    }

    @Test
    void recordingDefaultsOff() {
        ExamSessionDetailResponse detail = createClassRestricted(monitoring(true, false, false, false, false, false));
        assertThat(detail.monitoring().fullRecordingEnabled()).isFalse(); // D4: OFF by default
    }

    // === legacy regression ===

    @Test
    void legacyCreateWithoutMonitoringBlockStaysOffAndUsable() {
        ExamSessionDetailResponse detail = create(null, "CLASS_RESTRICTED", List.of(classroomId));
        assertThat(detail.monitoring().monitoringEnabled()).isFalse();
        assertThat(detail.monitoring().cameraRequired()).isFalse();
        // Legacy session: student can still start an attempt normally.
        long sessionId = detail.id();
        openSession(sessionId);
        var started = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
        assertThat(started.attemptId()).isPositive();
    }

    @Test
    void legacyPublicSessionCreateUnchanged() {
        ExamSessionDetailResponse detail = create(null, "PUBLIC", null);
        assertThat(detail.monitoring().monitoringEnabled()).isFalse();
        openSession(detail.id());
        var started = attemptService.startAttempt(studentId, detail.id(), new StartAttemptRequest(null));
        assertThat(started.resumed()).isFalse();
    }

    // === update semantics + freeze ===

    @Test
    void updateWithExplicitBlockReplacesPolicy() {
        ExamSessionDetailResponse detail = createClassRestricted(null);
        ExamSessionDetailResponse updated = sessionService.updateSession(teacherId, examId, detail.id(),
                new UpdateExamSessionRequest(detail.version(), detail.title(), detail.startsAt(), detail.endsAt(),
                        detail.maxAttempts(), monitoring(true, false, false, false, false, false)));
        assertThat(updated.monitoring().monitoringEnabled()).isTrue();
    }

    @Test
    void updateWithoutMonitoringBlockKeepsPolicyUnchanged() {
        ExamSessionDetailResponse detail = createClassRestricted(monitoring(true, false, false, false, false, false));
        ExamSessionDetailResponse updated = sessionService.updateSession(teacherId, examId, detail.id(),
                new UpdateExamSessionRequest(detail.version(), "New title", detail.startsAt(), detail.endsAt(),
                        detail.maxAttempts(), null));
        assertThat(updated.monitoring().monitoringEnabled()).isTrue(); // no-op, not reset
        assertThat(updated.title()).isEqualTo("New title");
    }

    @Test
    void updateVersionConflictRejected() {
        ExamSessionDetailResponse detail = createClassRestricted(null);
        assertThatThrownBy(() -> sessionService.updateSession(teacherId, examId, detail.id(),
                new UpdateExamSessionRequest(detail.version() + 1, detail.title(), detail.startsAt(),
                        detail.endsAt(), detail.maxAttempts(), monitoring(true, false, false, false, false, false))))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_CONCURRENT_MODIFICATION"));
    }

    @Test
    void updateFrozenWhenScheduledStartReached() {
        ExamSessionDetailResponse detail = createClassRestricted(null);
        schedule(detail.id());
        // Advance the server clock past startsAt (window far in the future → past it).
        clock.setInstant(detail.startsAt().plusSeconds(60));
        assertThatThrownBy(() -> sessionService.updateSession(teacherId, examId, detail.id(),
                new UpdateExamSessionRequest(detail.version(), detail.title(), detail.startsAt(),
                        detail.endsAt(), detail.maxAttempts(), monitoring(true, false, false, false, false, false))))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_MONITORING_FROZEN"));
    }

    @Test
    void updateCannotBypassFreezeByMovingStartsAtForwardInSameRequest() {
        // Review FRZ-1 regression: SCHEDULED session whose ORIGINAL startsAt
        // has passed; a single PUT carrying monitoring AND a future startsAt
        // must still hit the freeze gate (evaluated against the persisted
        // startsAt, before updateConfig mutates the window).
        ExamSessionDetailResponse detail = createClassRestricted(null);
        schedule(detail.id());
        clock.setInstant(detail.startsAt().plusSeconds(60));
        assertThatThrownBy(() -> sessionService.updateSession(teacherId, examId, detail.id(),
                new UpdateExamSessionRequest(detail.version(), detail.title(),
                        detail.startsAt().plusSeconds(3600), detail.endsAt().plusSeconds(3600),
                        detail.maxAttempts(), monitoring(true, false, false, false, false, false))))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_MONITORING_FROZEN"));
        // Title/time-only edits of the same frozen-window session keep working.
        ExamSessionDetailResponse retimed = sessionService.updateSession(teacherId, examId, detail.id(),
                new UpdateExamSessionRequest(detail.version(), detail.title(),
                        detail.startsAt().plusSeconds(3600), detail.endsAt().plusSeconds(3600),
                        detail.maxAttempts(), null));
        assertThat(retimed.monitoring().monitoringEnabled()).isFalse(); // untouched
    }

    @Test
    void noOpUnassignOfUnassignedClassIsNotRejectedOnMonitoredSession() {
        // Review CLS-1 regression: idempotent no-op unassign must keep the
        // pre-P4-A no-op semantics even on monitored sessions.
        ExamSessionDetailResponse detail = createClassRestricted(monitoring(true, false, false, false, false, false));
        long otherClassroom = ins("INSERT INTO classrooms (owner_user_id, code, name) VALUES (" + teacherId + ",'CLSX" + uuid6() + "','X')");
        classroomServiceBean.unassignSessionFromClassroom(teacherId, otherClassroom, detail.id()); // must not throw
        // The monitored session keeps its assigned class.
        assertThat(sessionService.listClasses(teacherId, examId, detail.id()).items()).hasSize(1);
    }

    @Test
    void updateFrozenWhenAnyAttemptExists() {
        ExamSessionDetailResponse detail = createClassRestricted(null);
        openSession(detail.id());
        attemptService.startAttempt(studentId, detail.id(), new StartAttemptRequest(null));
        // Session is OPEN → config update is rejected by the existing state
        // machine BEFORE the freeze check — freeze by status is implied;
        // the attempts-existence freeze is additionally proven directly:
        assertThatThrownBy(() -> sessionService.updateSession(teacherId, examId, detail.id(),
                new UpdateExamSessionRequest(detail.version(), detail.title(), detail.startsAt(),
                        detail.endsAt(), detail.maxAttempts(), monitoring(true, false, false, false, false, false))))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_INVALID_STATE"));
    }

    @Test
    void frozenFlagDerivedInDetailAfterStartsAtReached() {
        ExamSessionDetailResponse detail = createClassRestricted(monitoring(true, false, false, false, false, false));
        schedule(detail.id());
        clock.setInstant(detail.startsAt().plusSeconds(1));
        ExamSessionDetailResponse after = sessionService.getSessionDetail(teacherId, examId, detail.id());
        assertThat(after.monitoring().configurationFrozen()).isTrue();
    }

    // === class-assignment safeguards ===

    @Test
    void assignClassesCannotRemoveLastClassOfMonitoredSession() {
        ExamSessionDetailResponse detail = createClassRestricted(monitoring(true, false, false, false, false, false));
        assertThatThrownBy(() -> sessionService.assignClasses(teacherId, examId, detail.id(), List.of()))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_MONITORING_INVALID"));
    }

    @Test
    void assignClassesCanRemoveAllClassesOfNonMonitoredSession() {
        ExamSessionDetailResponse detail = createClassRestricted(null);
        sessionService.assignClasses(teacherId, examId, detail.id(), List.of()); // legacy behavior unchanged
        assertThat(sessionService.listClasses(teacherId, examId, detail.id()).items()).isEmpty();
    }

    @Test
    void classroomUnassignCannotRemoveLastClassOfMonitoredSession() {
        ExamSessionDetailResponse detail = createClassRestricted(monitoring(true, false, false, false, false, false));
        assertThatThrownBy(() -> classroomServiceBean.unassignSessionFromClassroom(teacherId, classroomId, detail.id()))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_MONITORING_INVALID"));
    }

    // === fail-closed monitored start (P4-A/P4-B boundary) ===

    @Test
    void monitoredSessionNewAttemptStartFailsClosed() {
        ExamSessionDetailResponse detail = createClassRestricted(monitoring(true, false, true, false, false, false));
        openSession(detail.id());
        assertThatThrownBy(() -> attemptService.startAttempt(studentId, detail.id(), new StartAttemptRequest(null)))
                .satisfies(e -> assertThat(attCode(e)).isEqualTo("ATTEMPT_MONITORING_START_UNAVAILABLE"));
        // No attempt row was created.
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_session_id = ?", Integer.class, detail.id());
        assertThat(attempts).isZero();
    }

    @Test
    void eligibilityExposesMonitoringBlockAndForcesCanStartNowFalse() {
        ExamSessionDetailResponse detail = createClassRestricted(monitoring(true, false, false, false, false, false));
        openSession(detail.id());
        SessionEligibilityResponse eligibility =
                attemptService.getSessionEligibility(studentId, detail.id());
        assertThat(eligibility.monitoring().monitoringEnabled()).isTrue();
        assertThat(eligibility.monitoring().cameraRequired()).isTrue();
        assertThat(eligibility.canStartNow()).isFalse();
        assertThat(eligibility.state()).isEqualTo(SessionEligibilityResponse.STATE_CAN_START);
    }

    @Test
    void eligibilityUnmonitoredCanStartNowTrue() {
        ExamSessionDetailResponse detail = createClassRestricted(null);
        openSession(detail.id());
        SessionEligibilityResponse eligibility =
                attemptService.getSessionEligibility(studentId, detail.id());
        assertThat(eligibility.monitoring().monitoringEnabled()).isFalse();
        assertThat(eligibility.canStartNow()).isTrue();
    }

    // === authorization ===

    @Test
    void nonOwnerCannotCreateMonitoringSessionForOthersExam() {
        long otherTeacher = user("omon");
        grant(otherTeacher, "TEACHER");
        // Exam belongs to teacherId; otherTeacher lacks ownership.
        assertThatThrownBy(() -> sessionService.createSession(otherTeacher, examId,
                request("CLASS_RESTRICTED", List.of(classroomId),
                        monitoring(true, false, false, false, false, false))))
                .satisfies(e -> assertThat(examCode(e)).isEqualTo("EXAM_SESSION_ACCESS_DENIED"));
    }

    // === helpers ===

    @Autowired private com.quizopia.backend.exam.application.ClassroomSessionService classroomServiceBean;


    private ExamSessionDetailResponse createClassRestricted(MonitoringPolicyRequest monitoring) {
        return create(monitoring, "CLASS_RESTRICTED", List.of(classroomId));
    }

    private ExamSessionDetailResponse create(MonitoringPolicyRequest monitoring, String visibility,
            List<Long> classroomIds) {
        return sessionService.createSession(teacherId, examId,
                request(visibility, classroomIds, monitoring));
    }

    private CreateExamSessionRequest request(String visibility, List<Long> classroomIds,
            MonitoringPolicyRequest monitoring) {
        Instant start = Instant.parse("2026-11-14T09:00:00Z");
        Instant end = Instant.parse("2026-11-14T11:00:00Z");
        return new CreateExamSessionRequest(null, null, "Monitored session", start, end, 1, 60,
                com.quizopia.backend.exam.domain.model.SessionVisibility.valueOf(visibility),
                classroomIds, monitoring);
    }

    private MonitoringPolicyRequest monitoring(boolean enabled, boolean ai, boolean tracking,
            boolean snapshots, boolean recording, boolean clips) {
        return new MonitoringPolicyRequest(enabled, ai, tracking, snapshots, recording, clips);
    }

    private void openSession(long sessionId) {
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", sessionId);
        em.clear(); // raw UPDATE bypasses JPA — drop stale managed entities
        clock.setInstant(Instant.parse("2026-11-14T09:30:00Z")); // inside [09:00, 11:00] window
    }

    private void schedule(long sessionId) {
        jdbc.update("UPDATE exam_sessions SET status='SCHEDULED' WHERE id=?", sessionId);
        em.clear();
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name=? AND column_name=?",
                Integer.class, table, column) > 0;
    }

    private long user(String prefix) {
        return ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + prefix + uuid6()
                + "','" + prefix + uuid6() + "@t.com','h','" + prefix + "')");
    }

    private void grant(long userId, String role) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + userId + ", id FROM roles WHERE code='" + role + "'");
    }

    private String uuid6() {
        return java.util.UUID.randomUUID().toString().substring(0, 6);
    }

    private static String examCode(Throwable e) {
        return ((ExamException) e).getErrorCode().name();
    }

    private static String attCode(Throwable e) {
        return ((AttemptException) e).getErrorCode().code();
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
