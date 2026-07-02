package com.hhtuann.backend.exam;

import com.hhtuann.backend.exam.application.ExamService;
import com.hhtuann.backend.exam.application.ExamSessionParticipantService;
import com.hhtuann.backend.exam.application.ExamSessionService;
import com.hhtuann.backend.exam.dto.AddParticipantsRequest;
import com.hhtuann.backend.exam.dto.CreateExamRequest;
import com.hhtuann.backend.exam.dto.CreateExamSessionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.hhtuann.backend.exam.exception.ExamException;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
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
 * Concurrency evidence for the 4 lifecycle endpoints (A3.2-3C). Non-transactional +
 * {@code @DirtiesContext(AFTER_CLASS)} so each service call runs in its own committed transaction
 * (separate persistence contexts). The pessimistic session lock serializes concurrent transitions,
 * so competing transitions resolve to exactly one valid state and never 500.
 *
 * <ul>
 *   <li>Same-transition ×2 (schedule/open/close/cancel) → both 200, no 500.</li>
 *   <li>open-vs-cancel on SCHEDULED → exactly one valid final state (OPEN or CANCELLED).</li>
 *   <li>close (lifecycle) vs block (participant) → block re-reads CLOSED → 409; participant untouched.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExamSessionLifecycleConcurrencyIntegrationTests {

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;
    @Autowired private ExamSessionService sessionService;
    @Autowired private ExamSessionParticipantService participantService;
    @Autowired private PlatformTransactionManager transactionManager;

    private static long runSeq = 0;

    private long teacherUserId;
    private long schoolId;
    private long examId;
    private long student1;

    @BeforeEach
    void setUp() {
        String u = "k" + (++runSeq);
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('kc" + u + "','kc" + u + "@t','h','KC" + u + "')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('KCS" + u + "','KC School " + u + "')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'KCC" + u + "')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp + ",'KCB','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        examId = examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "KCE" + u, "T", null)).id();
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(1, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(q, 0, null))))));
        long v1 = jdbc.queryForObject("SELECT id FROM exam_versions WHERE exam_id=? AND status='DRAFT'", Long.class, examId);
        jdbc.update("UPDATE exam_versions SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", v1);
        jdbc.update("UPDATE exams SET status='READY' WHERE id=?", examId);
        long uid = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('st" + u + "','st" + u + "@t','h','ST')");
        student1 = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + uid + "," + schoolId + ",'HS" + u + "')");
    }

    // -- Same-transition ×2: both 200, no 500 --

    @Test
    void concurrentScheduleNo500() throws Exception {
        long sid = draft("CS1", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        List<String> r = runTwo(lifecycleTask(() -> sessionService.scheduleSession(teacherUserId, sid)),
                lifecycleTask(() -> sessionService.scheduleSession(teacherUserId, sid)));
        assertThat(r).containsOnly("OK");
        assertStatus(sid, "SCHEDULED");
    }

    @Test
    void concurrentOpenNo500() throws Exception {
        long sid = scheduled("CO1");
        List<String> r = runTwo(lifecycleTask(() -> sessionService.openSession(teacherUserId, sid)),
                lifecycleTask(() -> sessionService.openSession(teacherUserId, sid)));
        assertThat(r).containsOnly("OK");
        assertStatus(sid, "OPEN");
        assertThat(openedAt(sid)).isNotNull();
    }

    @Test
    void concurrentCloseNo500() throws Exception {
        long sid = open("CC1");
        List<String> r = runTwo(lifecycleTask(() -> sessionService.closeSession(teacherUserId, sid)),
                lifecycleTask(() -> sessionService.closeSession(teacherUserId, sid)));
        assertThat(r).containsOnly("OK");
        assertStatus(sid, "CLOSED");
    }

    @Test
    void concurrentCancelNo500() throws Exception {
        long sid = draft("CX1", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        List<String> r = runTwo(lifecycleTask(() -> sessionService.cancelSession(teacherUserId, sid)),
                lifecycleTask(() -> sessionService.cancelSession(teacherUserId, sid)));
        assertThat(r).containsOnly("OK");
        assertStatus(sid, "CANCELLED");
    }

    // -- Competing transitions: exactly one valid final state --

    @Test
    void openVsCancelProducesOneValidState() throws Exception {
        long sid = scheduled("OVC1"); // SCHEDULED, valid window
        List<String> r = runTwo(lifecycleTask(() -> sessionService.openSession(teacherUserId, sid)),
                lifecycleTask(() -> sessionService.cancelSession(teacherUserId, sid)));
        // Exactly one 200, the other 409 (serialized). No 500.
        assertThat(r).as("one OK + one INVALID_STATE").containsExactlyInAnyOrder("OK", "EXAM_SESSION_INVALID_STATE");
        String status = status(sid);
        assertThat(status).as("final state must be OPEN or CANCELLED, never a mix").isIn("OPEN", "CANCELLED");
        // Invariant holds for whichever state won.
        if ("OPEN".equals(status)) {
            assertThat(openedAt(sid)).isNotNull();
            assertThat(closedAt(sid)).isNull();
        } else {
            assertThat(openedAt(sid)).isNull();
            assertThat(closedAt(sid)).isNull();
        }
    }

    // -- Lifecycle vs participant: participant op re-reads the new state and rejects --

    @Test
    void closeVsBlockParticipantRaceRejectsBlock() throws Exception {
        // Build the session up to OPEN, adding the participant while still DRAFT (add requires DRAFT/SCHEDULED).
        long sid = draft("CVR1", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        long pid = addParticipant(sid, student1); // DRAFT — allowed
        sessionService.scheduleSession(teacherUserId, sid); // → SCHEDULED
        sessionService.openSession(teacherUserId, sid); // → OPEN, participant ELIGIBLE
        CountDownLatch aLocked = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Thread A: lifecycle close — lock session, transition OPEN→CLOSED, hold, then commit.
        Future<?> a = pool.submit(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                sessionService.closeSession(teacherUserId, sid); // locks + transitions + (held open until commit)
                aLocked.countDown();
                try {
                    aMayCommit.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        assertThat(aLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // Thread B: participant block — must block on the session lock, then re-read CLOSED → 409.
        Future<String> b = pool.submit(() -> {
            try {
                participantService.blockParticipant(teacherUserId, sid, pid);
                return "OK";
            } catch (ExamException e) {
                return e.getErrorCode().name();
            } catch (Exception e) {
                return e.getClass().getSimpleName();
            }
        });
        aMayCommit.countDown();
        a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String result = b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(result).isEqualTo("EXAM_SESSION_INVALID_STATE");
        assertStatus(sid, "CLOSED");
        // Participant NOT mutated by the lifecycle (still ELIGIBLE — block was rejected).
        assertThat(participantStatus(pid)).isEqualTo("ELIGIBLE");
    }

    // -- Harness --

    private Callable<String> lifecycleTask(Runnable action) {
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

    private List<String> runTwo(Callable<String> ta, Callable<String> tb) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> fa = pool.submit(() -> { start.await(); return ta.call(); });
        Future<String> fb = pool.submit(() -> { start.await(); return tb.call(); });
        start.countDown();
        String ra = fa.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String rb = fb.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return List.of(ra, rb);
    }

    // -- State fixture helpers --

    private long draft(String code, Instant starts, Instant ends) {
        return sessionService.createSession(teacherUserId, new CreateExamSessionRequest(examId, 1, code, "T", starts, ends, 1)).id();
    }

    private long scheduled(String code) {
        long sid = draft(code, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        sessionService.scheduleSession(teacherUserId, sid);
        return sid;
    }

    private long open(String code) {
        long sid = scheduled(code);
        sessionService.openSession(teacherUserId, sid);
        return sid;
    }

    private long addParticipant(long sessionId, long studentProfileId) {
        participantService.addParticipants(teacherUserId, sessionId, new AddParticipantsRequest(List.of(studentProfileId)));
        return jdbc.queryForObject("SELECT id FROM exam_session_participants WHERE exam_session_id=? AND student_profile_id=?", Long.class, sessionId, studentProfileId);
    }

    private void assertStatus(long sessionId, String expected) {
        assertThat(status(sessionId)).isEqualTo(expected);
    }

    private String status(long sessionId) {
        return jdbc.queryForObject("SELECT status FROM exam_sessions WHERE id=?", String.class, sessionId);
    }

    private Object openedAt(long sessionId) {
        return jdbc.queryForObject("SELECT opened_at FROM exam_sessions WHERE id=?", Object.class, sessionId);
    }

    private Object closedAt(long sessionId) {
        return jdbc.queryForObject("SELECT closed_at FROM exam_sessions WHERE id=?", Object.class, sessionId);
    }

    private String participantStatus(long pid) {
        return jdbc.queryForObject("SELECT status FROM exam_session_participants WHERE id=?", String.class, pid);
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
