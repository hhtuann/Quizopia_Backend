package com.quizopia.backend.realtime;

import com.quizopia.backend.attempt.application.AttemptAutosaveService;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.application.AttemptSubmitService;
import com.quizopia.backend.attempt.dto.SaveAnswerRequest;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.dto.SubmitRequest;
import com.quizopia.backend.realtime.event.RealtimeEventEnvelope;
import com.quizopia.backend.realtime.support.RepositoryLockEntryProbe.LockPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realtime event-concurrency with LIVE STOMP capture (B1R4-B1 §1-§4). Refactored from the DB-only
 * version to extend {@link RealtimeStompTestBase}: a teacher subscribes the topic BEFORE the workers
 * fire, real STOMP frames are captured + parsed to {@link RealtimeEventEnvelope}, and exact event
 * count/type/payload/no-duplicate is asserted alongside the DB result.
 *
 * <p><b>Fresh fixtures:</b> start scenarios begin from ZERO attempts (no pre-existing IN_PROGRESS).
 * <b>True overlap:</b> the start scenarios use a held-transaction barrier — worker A opens an outer
 * {@link TransactionTemplate}, calls {@code startAttempt} (acquires the participant {@code FOR UPDATE}
 * lock), signals held, and waits; worker B calls {@code startAttempt} and BLOCKS on that lock. The main
 * thread asserts B is not complete while A holds the lock (true overlap), then releases A.
 *
 * <p><b>DELIVERY_ORDER_UNSPECIFIED</b> — the frozen contract (§9.6) fixes event TYPES/nullability but
 * does not guarantee inter-event transport delivery order (the simple broker is an async ExecutorChannel).
 * Assertions are by exact count + type + payload + distinct eventIds, NOT by list index.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@SuppressWarnings({"null"})
class RealtimeAttemptEventConcurrencyIntegrationTests extends RealtimeStompTestBase {

    @Autowired private AttemptService attemptService;
    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private PlatformTransactionManager txm;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.quizopia.backend.realtime.support.RepositoryLockEntryProbe lockProbe;

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }

    private record FreshSession(long sessionId, long teacherId, String teacherToken, long studentId) {}
    private record TwoStudentSession(long sessionId, long teacherId, String teacherToken, long s1, long s2) {}
    private record ExistingAttempt(long sessionId, long teacherId, String teacherToken, long studentId, long attemptId, long aqId) {}

    // === §3.1 same-student concurrent start (FRESH, zero attempts) + true-overlap barrier ===
    @Test
    void sameStudentConcurrentStartProducesOneEventPair() throws Exception {
        FreshSession f = freshSession();
        try (StompTestConnection teacher = connect(f.teacherToken)) {
            TopicCapture cap = subscribe(teacher, f.sessionId, 2);
            awaitAccepted(acceptedDestination(topic(f.sessionId)), 5);
            assertPrecondition(f.sessionId, 0);

            CountDownLatch aHeld = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // Worker A: outer tx → startAttempt (participant lock held) → signal → await release.
                Future<?> a = pool.submit(() -> tx().executeWithoutResult(status -> {
                    attemptService.startAttempt(f.studentId, f.sessionId, new StartAttemptRequest(null));
                    aHeld.countDown();
                    awaitLatch(release, "release", 30);
                }));
                assertThat(awaitLatch(aHeld, "aHeld", 15)).as("worker A entered tx + locked participant").isTrue();
                Future<?> b = pool.submit(() -> {
                    lockProbe.armCurrentThread(com.quizopia.backend.realtime.support.RepositoryLockEntryProbe.LockPoint.SESSION_FOR_UPDATE);
                    return attemptService.startAttempt(f.studentId, f.sessionId, new StartAttemptRequest(null));
                });
                assertThat(lockProbe.awaitEntered(com.quizopia.backend.realtime.support.RepositoryLockEntryProbe.LockPoint.SESSION_FOR_UPDATE, 10)).as("worker B reached ExamSessionRepository.findByIdForUpdate").isTrue();
                assertThat(b.isDone()).as("worker B must be blocked on A's lock (true overlap)").isFalse();
                release.countDown();
                a.get(30, TimeUnit.SECONDS);
                b.get(30, TimeUnit.SECONDS);
            } finally { shutdown(pool); }

            assertThat(cap.latch.await(20, TimeUnit.SECONDS)).isTrue();
            assertNoExtraFrame(cap, 2);
            assertEventCounts(cap.payloads, 1, 1); // 1 ATTEMPT_STARTED + 1 ACTIVE_COUNT_CHANGED
            assertThat(activeCount(f.sessionId)).isEqualTo(1L);
        }
    }

    // === §3.2 same-key concurrent submit — HELD-TX true-overlap barrier ===
    @Test
    void sameKeyConcurrentSubmitProducesOneEventPair() throws Exception {
        ExistingAttempt f = existingAttempt();
        String key = "same-" + UUID.randomUUID();
        try (StompTestConnection teacher = connect(f.teacherToken)) {
            TopicCapture cap = subscribe(teacher, f.sessionId, 2);
            awaitAccepted(acceptedDestination(topic(f.sessionId)), 5);

            CountDownLatch aHeld = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> a = pool.submit(() -> tx().executeWithoutResult(status -> {
                    submitService.submitAttempt(f.studentId, f.attemptId, new SubmitRequest(key));
                    aHeld.countDown();
                    awaitLatch(release, "release", 30);
                }));
                assertThat(awaitLatch(aHeld, "aHeld", 15)).as("worker A entered tx + locked attempt").isTrue();
                Future<?> b = pool.submit(() -> {
                    lockProbe.armCurrentThread(LockPoint.ATTEMPT_FOR_UPDATE);
                    return submitService.submitAttempt(f.studentId, f.attemptId, new SubmitRequest(key));
                });
                assertThat(lockProbe.awaitEntered(LockPoint.ATTEMPT_FOR_UPDATE, 10)).as("worker B reached AttemptRepository.findByIdForUpdate").isTrue();
                assertThat(b.isDone()).as("worker B must be blocked on A's attempt lock (true overlap)").isFalse();
                release.countDown();
                a.get(30, TimeUnit.SECONDS);
                b.get(30, TimeUnit.SECONDS);
            } finally { shutdown(pool); }

            assertThat(cap.latch.await(20, TimeUnit.SECONDS)).isTrue();
            assertNoExtraFrame(cap, 2);
            assertEventCounts(cap.payloads, 1, 1);
            assertThat(idempotencyRows(f.attemptId)).as("exactly one idempotency record").isEqualTo(1);
        }
    }

    // === §3.3 different-key concurrent submit — HELD-TX true-overlap barrier ===
    @Test
    void differentKeyConcurrentSubmitProducesOneEventPair() throws Exception {
        ExistingAttempt f = existingAttempt();
        try (StompTestConnection teacher = connect(f.teacherToken)) {
            TopicCapture cap = subscribe(teacher, f.sessionId, 2);
            awaitAccepted(acceptedDestination(topic(f.sessionId)), 5);

            CountDownLatch aHeld = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> a = pool.submit(() -> {
                    tx().executeWithoutResult(status -> {
                        wrap(() -> submitService.submitAttempt(f.studentId, f.attemptId, new SubmitRequest("ka-" + UUID.randomUUID())));
                        aHeld.countDown();
                        awaitLatch(release, "release", 30);
                    });
                    return null;
                });
                assertThat(awaitLatch(aHeld, "aHeld", 15)).as("worker A entered tx + locked attempt").isTrue();
                Future<Object> b = pool.submit(() -> {
                    lockProbe.armCurrentThread(LockPoint.ATTEMPT_FOR_UPDATE);
                    return wrap(() -> submitService.submitAttempt(f.studentId, f.attemptId, new SubmitRequest("kb-" + UUID.randomUUID())));
                });
                assertThat(lockProbe.awaitEntered(LockPoint.ATTEMPT_FOR_UPDATE, 10)).as("worker B reached AttemptRepository.findByIdForUpdate").isTrue();
                assertThat(b.isDone()).as("worker B must be blocked on A's attempt lock (true overlap)").isFalse();
                release.countDown();
                a.get(30, TimeUnit.SECONDS);
                Object rb = b.get(30, TimeUnit.SECONDS);
                long oks = (rb instanceof com.quizopia.backend.attempt.dto.SubmitResponse ? 1 : 0);
                assertThat(oks).as("B returns the cached/conflict result (not a new success)").isZero();
                assertThat(idempotencyRows(f.attemptId)).isEqualTo(1);
            } finally { shutdown(pool); }

            assertThat(cap.latch.await(20, TimeUnit.SECONDS)).isTrue();
            assertNoExtraFrame(cap, 2);
            assertEventCounts(cap.payloads, 1, 1);
        }
    }

    // === §3.4 submit vs autosave — HELD-TX true-overlap barrier ===
    @Test
    void submitVsAutosaveProducesOneSubmitEventPair() throws Exception {
        ExistingAttempt f = existingAttempt();
        try (StompTestConnection teacher = connect(f.teacherToken)) {
            TopicCapture cap = subscribe(teacher, f.sessionId, 2);
            awaitAccepted(acceptedDestination(topic(f.sessionId)), 5);

            CountDownLatch aHeld = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // Worker A: outer tx → autosave (acquires attempt lock) → signal held → await release.
                Future<?> a = pool.submit(() -> tx().executeWithoutResult(status -> {
                    autosaveService.saveAnswer(f.studentId, f.attemptId, singleChoice(f.aqId, "A"));
                    aHeld.countDown();
                    awaitLatch(release, "release", 30);
                }));
                assertThat(awaitLatch(aHeld, "aHeld", 15)).as("worker A (autosave) entered tx + locked attempt").isTrue();
                Future<Object> b = pool.submit(() -> {
                    lockProbe.armCurrentThread(LockPoint.ATTEMPT_FOR_UPDATE);
                    return wrap(() -> submitService.submitAttempt(f.studentId, f.attemptId, new SubmitRequest("svs-" + UUID.randomUUID())));
                });
                assertThat(lockProbe.awaitEntered(LockPoint.ATTEMPT_FOR_UPDATE, 10)).as("worker B reached AttemptRepository.findByIdForUpdate").isTrue();
                assertThat(b.isDone()).as("worker B (submit) must be blocked on A's attempt lock (true overlap)").isFalse();
                release.countDown();
                a.get(30, TimeUnit.SECONDS);
                b.get(30, TimeUnit.SECONDS);
            } finally { shutdown(pool); }

            assertThat(cap.latch.await(20, TimeUnit.SECONDS)).isTrue();
            assertNoExtraFrame(cap, 2);
            assertEventCounts(cap.payloads, 1, 1); // autosave produces NO canonical event
            String status = jdbc.queryForObject("SELECT status FROM attempts WHERE id=" + f.attemptId, String.class);
            assertThat(status).isEqualTo("SUBMITTED");
        }
    }

    // === §3.5 two-student concurrent start (FRESH, zero attempts) + true-overlap barrier ===
    @Test
    void twoStudentConcurrentStartProducesTwoEventPairs() throws Exception {
        TwoStudentSession f = freshSessionTwoStudents();
        try (StompTestConnection teacher = connect(f.teacherToken)) {
            TopicCapture cap = subscribe(teacher, f.sessionId, 4); // 2 started + 2 count
            awaitAccepted(acceptedDestination(topic(f.sessionId)), 5);
            assertPrecondition(f.sessionId, 0);

            CountDownLatch aHeld = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // Worker A (s1): outer tx → startAttempt (acquires the SESSION lock) → signal → await release.
                Future<?> a = pool.submit(() -> tx().executeWithoutResult(status -> {
                    attemptService.startAttempt(f.s1, f.sessionId, new StartAttemptRequest(null));
                    aHeld.countDown();
                    awaitLatch(release, "release", 30);
                }));
                assertThat(awaitLatch(aHeld, "aHeld", 15)).as("worker A entered tx + locked session").isTrue();
                Future<?> b = pool.submit(() -> {
                    lockProbe.armCurrentThread(LockPoint.SESSION_FOR_UPDATE);
                    return attemptService.startAttempt(f.s2, f.sessionId, new StartAttemptRequest(null));
                });
                assertThat(lockProbe.awaitEntered(LockPoint.SESSION_FOR_UPDATE, 10)).as("worker B reached ExamSessionRepository.findByIdForUpdate").isTrue();
                assertThat(b.isDone()).as("worker B must be blocked on A's session lock (true overlap)").isFalse();
                release.countDown();
                a.get(30, TimeUnit.SECONDS);
                b.get(30, TimeUnit.SECONDS);
            } finally { shutdown(pool); }

            assertThat(cap.latch.await(20, TimeUnit.SECONDS)).isTrue();
            assertNoExtraFrame(cap, 4);
            assertEventCounts(cap.payloads, 2, 2); // 2 ATTEMPT_STARTED + 2 ACTIVE_COUNT_CHANGED
            assertThat(activeCount(f.sessionId)).isEqualTo(2L);
            // Each attemptId appears exactly once across the ATTEMPT_STARTED events (no duplicate).
            Set<Long> attemptIds = new HashSet<>();
            for (RealtimeEventEnvelope ev : parse(cap.payloads)) {
                if (ev.eventType() != null && ev.eventType().contains("ATTEMPT_STARTED")) attemptIds.add(ev.attemptId());
            }
            assertThat(attemptIds).as("two distinct attemptIds, each once").hasSize(2);
        }
    }

    // ============================ fixtures ============================

    private FreshSession freshSession() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        long session = createTeacherOwnedOpenSession("fc-" + tag);
        long teacher = teacherIdForSession(session);
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + session, Long.class);
        long student = addStudent("fs-" + tag, school, session, teacher);
        return new FreshSession(session, teacher, accessToken(teacher, "fc-tch", List.of("TEACHER")), student);
    }

    private TwoStudentSession freshSessionTwoStudents() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        long session = createTeacherOwnedOpenSession("tc-" + tag);
        long teacher = teacherIdForSession(session);
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + session, Long.class);
        long s1 = addStudent("t1-" + tag, school, session, teacher);
        long s2 = addStudent("t2-" + tag, school, session, teacher);
        return new TwoStudentSession(session, teacher, accessToken(teacher, "tc-tch", List.of("TEACHER")), s1, s2);
    }

    private ExistingAttempt existingAttempt() {
        FreshSession f = freshSession();
        long attemptId = attemptService.startAttempt(f.studentId, f.sessionId, new StartAttemptRequest(null)).attemptId();
        long aq = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attemptId + " LIMIT 1", Long.class);
        return new ExistingAttempt(f.sessionId, f.teacherId, f.teacherToken, f.studentId, attemptId, aq);
    }

    private long addStudent(String tag, long school, long session, long teacher) {
        long uid = insertUserWithRole(tag, "STUDENT");
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + uid + "," + school + ",'SC" + tag + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES ("
                + school + "," + session + ",(SELECT id FROM student_profiles WHERE user_id=" + uid + ")," + teacher + ")");
        return uid;
    }

    // ============================ STOMP helpers ============================

    private static String topic(long sid) { return "/topic/exam-sessions/" + sid; }

    private TopicCapture subscribe(StompTestConnection conn, long sid, int expected) {
        CopyOnWriteArrayList<byte[]> payloads = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(expected);
        conn.subscribe(topic(sid), new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) { payloads.add((byte[]) payload); latch.countDown(); }
        });
        return new TopicCapture(payloads, latch);
    }

    private RealtimeEventEnvelope[] parse(List<byte[]> payloads) {
        return payloads.stream().map(b -> {
            try { return objectMapper.readValue(b, RealtimeEventEnvelope.class); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).toArray(RealtimeEventEnvelope[]::new);
    }

    /** DELIVERY_ORDER_UNSPECIFIED — assert by exact count of each type, not by index. */
    private void assertEventCounts(List<byte[]> payloads, int attemptEvents, int countEvents) {
        RealtimeEventEnvelope[] ev = parse(payloads);
        long started = countType(ev, "ATTEMPT_STARTED");
        long submitted = countType(ev, "ATTEMPT_SUBMITTED");
        long count = countType(ev, "ACTIVE_COUNT_CHANGED");
        long attemptTotal = started + submitted;
        assertThat(attemptTotal).as("attempt canonical events (STARTED or SUBMITTED)").isEqualTo(attemptEvents);
        assertThat(count).as("ACTIVE_COUNT_CHANGED events").isEqualTo(countEvents);
        long distinctIds = java.util.Arrays.stream(ev).map(RealtimeEventEnvelope::eventId).filter(java.util.Objects::nonNull).distinct().count();
        assertThat(distinctIds).as("every event carries a distinct eventId (no duplicate)").isEqualTo(ev.length);
    }

    /** After the expected latch count is received, asserts no extra/delayed-duplicate frame in a bounded window. */
    private static void assertNoExtraFrame(TopicCapture cap, int expectedSize) throws InterruptedException {
        assertThat(cap.payloads).as("exact expected frame count").hasSize(expectedSize);
        CountDownLatch extra = new CountDownLatch(1);
        assertThat(extra.await(2, TimeUnit.SECONDS))
                .as("no delayed duplicate frame within bounded window").isFalse();
    }

    private static long countType(RealtimeEventEnvelope[] ev, String type) {
        return java.util.Arrays.stream(ev).filter(e -> type.equals(e.eventType())).count();
    }

    // ============================ DB helpers ============================

    private void assertPrecondition(long sessionId, int expectedAttempts) {
        long n = jdbc.queryForObject("SELECT count(*) FROM attempts WHERE exam_session_id=?", Long.class, sessionId);
        assertThat(n).as("DB precondition: zero attempts before workers fire").isEqualTo(expectedAttempts);
    }

    private long activeCount(long sessionId) {
        return jdbc.queryForObject("SELECT count(*) FROM attempts WHERE exam_session_id=? AND status='IN_PROGRESS'", Long.class, sessionId);
    }

    private long idempotencyRows(long attemptId) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE attempt_id=?", Long.class, attemptId);
    }

    private SaveAnswerRequest singleChoice(long aqId, String key) {
        ObjectNode node = objectMapper.createObjectNode().put("selectedOptionKey", key);
        return new SaveAnswerRequest(aqId, null, node, 1, null);
    }

    // ============================ concurrency helpers ============================

    private static boolean awaitLatch(CountDownLatch latch, String label, long timeoutSeconds) {
        try { return latch.await(timeoutSeconds, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(label + " interrupted", e); }
    }

    private static boolean awaitLatch(CountDownLatch latch, String label) {
        try { return latch.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(label + " interrupted", e); }
    }

    private interface Body { Object run() throws Exception; }
    private static Object wrap(Body body) { try { return body.run(); } catch (RuntimeException e) { return e; } catch (Exception e) { return new RuntimeException(e); } }
    private static long countSubmitResponses(Future<Object> a, Future<Object> b) {
        long oks = 0;
        try { if (a.get(30, TimeUnit.SECONDS) instanceof com.quizopia.backend.attempt.dto.SubmitResponse) oks++; } catch (Exception ignored) {}
        try { if (b.get(30, TimeUnit.SECONDS) instanceof com.quizopia.backend.attempt.dto.SubmitResponse) oks++; } catch (Exception ignored) {}
        return oks;
    }

    private static void shutdown(ExecutorService pool) {
        pool.shutdown();
        try { if (!pool.awaitTermination(15, TimeUnit.SECONDS)) { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); pool.shutdownNow(); }
    }

    private record TopicCapture(List<byte[]> payloads, CountDownLatch latch) {}
}
