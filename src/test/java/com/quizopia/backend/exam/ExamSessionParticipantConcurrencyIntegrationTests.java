package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.application.ExamSessionParticipantService;
import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.exam.dto.AddParticipantsRequest;
import com.quizopia.backend.exam.dto.CreateExamRequest;
import com.quizopia.backend.exam.dto.CreateExamSessionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency evidence for the A3.2-3B remediation (F3/F4/F5). Non-transactional +
 * {@code @DirtiesContext(AFTER_CLASS)} so each service call runs in its own committed
 * transaction (separate persistence contexts) and committed data is discarded after the class.
 *
 * <ul>
 *   <li>F3 — two concurrent adds sharing one student: the pessimistic session lock serializes
 *       them, so there is no unique-constraint race, no partial row, no lost insert.</li>
 *   <li>F4 — concurrent block/unblock: session+participant pessimistic locks serialize the
 *       toggles and force a fresh read; no {@code @Version} OptimisticLockException, no 500.</li>
 *   <li>F5 — a concurrent lifecycle state change: the mutating call waits for the session lock,
 *       re-reads the new (invalid) state and rejects; no participant mutation slips through.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExamSessionParticipantConcurrencyIntegrationTests {

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;
    @Autowired private ExamSessionService sessionService;
    @Autowired private ExamSessionParticipantService participantService;
    @Autowired private PlatformTransactionManager transactionManager;

    /** Monotonic per-{@code @BeforeEach} suffix so each test gets unique constrained identifiers
     *  (this class is non-transactional; committed data accumulates across the 5 tests). */
    private static long runSeq = 0;

    private long teacherUserId;
    private long schoolId;
    private long examId;
    private long questionId;
    private long sharedSessionId; // shared DRAFT session for F3/F4
    private long student1, student2, student3, student4, student5, student6;

    @BeforeEach
    void setUp() {
        String u = "r" + (++runSeq);
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('rc" + u + "','rc" + u + "@t','h','RC" + u + "')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('RCC" + u + "','RC School " + u + "')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'RCC" + u + "')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp + ",'RCB','Bank')");
        questionId = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + questionId + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        // Published exam
        examId = examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "RCE" + u, "T", null)).id();
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(1, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(questionId, 0, null))))));
        long v1 = jdbc.queryForObject("SELECT id FROM exam_versions WHERE exam_id=? AND status='DRAFT'", Long.class, examId);
        jdbc.update("UPDATE exam_versions SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", v1);
        jdbc.update("UPDATE exams SET status='READY' WHERE id=?", examId);
        // Shared DRAFT session (future window)
        Instant starts = Instant.now().plusSeconds(3600);
        sharedSessionId = sessionService.createSession(teacherUserId,
                new CreateExamSessionRequest(examId, 1, "RCS" + u, "RC Session", starts, starts.plusSeconds(7200), 1)).id();
        // Students
        student1 = createStudent("RC01" + u, "S1");
        student2 = createStudent("RC02" + u, "S2");
        student3 = createStudent("RC03" + u, "S3");
        student4 = createStudent("RC04" + u, "S4");
        student5 = createStudent("RC05" + u, "S5");
        student6 = createStudent("RC06" + u, "S6");
    }

    // -- F3: concurrent add with a shared student --

    @Test
    void concurrentAddWithSharedStudentSerializesAndLeavesNoPartialRows() throws Exception {
        // The pessimistic session lock (F5) serializes the two adds. The second add re-reads
        // committed state and dedupes the shared student → no unique-constraint race, no 409,
        // no partial row, no lost insert. This is strictly stronger than "one request gets 409".
        Callable<String> taskA = toggleTask(() -> participantService.addParticipants(teacherUserId, sharedSessionId,
                new AddParticipantsRequest(List.of(student1, student2))));
        Callable<String> taskB = toggleTask(() -> participantService.addParticipants(teacherUserId, sharedSessionId,
                new AddParticipantsRequest(List.of(student1, student3))));

        List<String> results = runTwoConcurrent(taskA, taskB);
        assertThat(results).as("both adds must succeed (no 409, no 500)").containsOnly("OK");

        // Shared student present exactly once; both uniques present; no partial/duplicate rows.
        long sharedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_session_participants WHERE exam_session_id=? AND student_profile_id=?",
                Long.class, sharedSessionId, student1);
        assertThat(sharedCount).isEqualTo(1L);
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_session_participants WHERE exam_session_id=?",
                Long.class, sharedSessionId);
        assertThat(total).as("shared + uniqueA + uniqueB").isEqualTo(3L);
    }

    // -- F4: concurrent block (no OptimisticLockException / 500) --

    @Test
    void concurrentBlockNoOptimisticLockException() throws Exception {
        long pid = addParticipant(student4); // ELIGIBLE
        List<String> results = runTwoConcurrent(
                toggleTask(() -> participantService.blockParticipant(teacherUserId, sharedSessionId, pid)),
                toggleTask(() -> participantService.blockParticipant(teacherUserId, sharedSessionId, pid)));
        assertThat(results).as("both blocks must return 200 (no 500)").containsOnly("OK");
        assertParticipantStatus(pid, "BLOCKED");
        assertThat(blockedAt(pid)).as("BLOCKED requires blockedAt").isNotNull();
    }

    @Test
    void concurrentUnblockNoOptimisticLockException() throws Exception {
        long pid = addParticipant(student5); // ELIGIBLE
        participantService.blockParticipant(teacherUserId, sharedSessionId, pid); // → BLOCKED (committed)
        List<String> results = runTwoConcurrent(
                toggleTask(() -> participantService.unblockParticipant(teacherUserId, sharedSessionId, pid)),
                toggleTask(() -> participantService.unblockParticipant(teacherUserId, sharedSessionId, pid)));
        assertThat(results).as("both unblocks must return 200 (no 500)").containsOnly("OK");
        assertParticipantStatus(pid, "ELIGIBLE");
        assertThat(blockedAt(pid)).as("ELIGIBLE requires blockedAt null").isNull();
    }

    @Test
    void concurrentMixedBlockUnblockNo500AndInvariantHolds() throws Exception {
        long pid = addParticipant(student6); // ELIGIBLE
        List<String> results = runTwoConcurrent(
                toggleTask(() -> participantService.blockParticipant(teacherUserId, sharedSessionId, pid)),
                toggleTask(() -> participantService.unblockParticipant(teacherUserId, sharedSessionId, pid)));
        assertThat(results).as("mixed toggles must not 500").containsOnly("OK");
        // Final state is valid per the last tx to win the lock; invariant (status ↔ blockedAt) holds.
        String status = jdbc.queryForObject("SELECT status FROM exam_session_participants WHERE id=?", String.class, pid);
        Object blockedAt = blockedAt(pid);
        if ("BLOCKED".equals(status)) {
            assertThat(blockedAt).isNotNull();
        } else {
            assertThat(status).isEqualTo("ELIGIBLE");
            assertThat(blockedAt).isNull();
        }
    }

    // -- F5: state race — mutation waits for the session lock and re-reads the new state --

    @Test
    void blockWaitsForSessionLockAndRejectsWhenSessionClosedConcurrently() throws Exception {
        // Dedicated session so the CLOSED flip never leaks into other tests.
        Instant starts = Instant.now().plusSeconds(3600);
        long sessionId = sessionService.createSession(teacherUserId,
                new CreateExamSessionRequest(examId, 1, "RC5", "State Race", starts, starts.plusSeconds(7200), 1)).id();
        long pid = jdbc.queryForObject(
                "INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, status, added_by, version) "
                        + "VALUES (?,?,?,?,?,0) RETURNING id", Long.class, schoolId, sessionId, student1, "ELIGIBLE", teacherUserId);

        CountDownLatch aLocked = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Thread A: open a tx, flip the session to CLOSED (holds the row lock), then pause before commit.
        Future<?> a = pool.submit(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE exam_sessions SET status='CLOSED', opened_at=now()-interval '2 hours', closed_at=now() WHERE id=?", sessionId);
                aLocked.countDown();
                try {
                    aMayCommit.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        assertThat(aLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("A must acquire the session lock first").isTrue();

        // Thread B: blockParticipant must block on the session lock, then re-read CLOSED → reject.
        Future<String> b = pool.submit(() -> {
            try {
                participantService.blockParticipant(teacherUserId, sessionId, pid);
                return "OK";
            } catch (ExamException e) {
                return e.getErrorCode().name();
            } catch (Exception e) {
                return e.getClass().getSimpleName();
            }
        });

        // Release A to commit (CLOSED persists, lock released). B then unblocks and re-reads CLOSED.
        aMayCommit.countDown();
        a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String result = b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(result).as("B must reject after re-reading the concurrently-closed session")
                .isEqualTo("EXAM_SESSION_INVALID_STATE");
        // No participant mutation slipped through.
        assertParticipantStatusFor(sessionId, pid, "ELIGIBLE");
    }

    // -- Concurrency harness --

    /** Wraps an action so the latch-started task reports "OK" or the exception short name. */
    private Callable<String> toggleTask(Runnable action) {
        return () -> {
            try {
                action.run();
                return "OK";
            } catch (ExamException e) {
                return e.getErrorCode().name();
            } catch (Exception e) {
                return e.getClass().getSimpleName();
            }
        };
    }

    private List<String> runTwoConcurrent(Callable<String> taskA, Callable<String> taskB) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> gatedA = () -> { start.await(); return taskA.call(); };
        Callable<String> gatedB = () -> { start.await(); return taskB.call(); };
        Future<String> fa = pool.submit(gatedA);
        Future<String> fb = pool.submit(gatedB);
        start.countDown();
        String ra = fa.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String rb = fb.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return List.of(ra, rb);
    }

    // -- Helpers --

    private long addParticipant(long studentProfileId) {
        participantService.addParticipants(teacherUserId, sharedSessionId, new AddParticipantsRequest(List.of(studentProfileId)));
        return participantId(sharedSessionId, studentProfileId);
    }

    private long participantId(long sessionId, long studentProfileId) {
        return jdbc.queryForObject("SELECT id FROM exam_session_participants WHERE exam_session_id=? AND student_profile_id=?",
                Long.class, sessionId, studentProfileId);
    }

    private void assertParticipantStatus(long pid, String expected) {
        assertParticipantStatusFor(sharedSessionId, pid, expected);
    }

    private void assertParticipantStatusFor(long sessionId, long pid, String expected) {
        String status = jdbc.queryForObject(
                "SELECT status FROM exam_session_participants WHERE id=? AND exam_session_id=?",
                String.class, pid, sessionId);
        assertThat(status).isEqualTo(expected);
    }

    private Object blockedAt(long pid) {
        return jdbc.queryForObject("SELECT blocked_at FROM exam_session_participants WHERE id=?", Object.class, pid);
    }

    private long createStudent(String code, String name) {
        long uid = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + code + "','" + code + "@t','h','" + name + "')");
        return insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + uid + "," + schoolId + ",'" + code + "')");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
