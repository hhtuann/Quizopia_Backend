package com.hhtuann.backend.realtime;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
import com.hhtuann.backend.exam.application.ExamSessionService;
import com.hhtuann.backend.realtime.event.RealtimeEventEnvelope;
import com.hhtuann.backend.realtime.event.RealtimeEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AFTER_COMMIT event-delivery closure (Day 7 B1R4-B §6-§10). Proves, with real STOMP + parsed
 * envelopes, that canonical events fire ONLY after the business transaction commits — never while it
 * is held uncommitted, never after a rollback, and never for no-op transitions (resume, cached retry).
 *
 * <p>Mechanism: a worker thread opens an outer {@link TransactionTemplate} and calls the production
 * service inside it; the outer tx stays uncommitted (held latch → release latch) so the main thread can
 * assert zero outbound events before release, then the full event matrix after commit. Rollback uses
 * {@code setRollbackOnly()}. No {@code Thread.sleep}; every wait is a bounded latch/future.
 */
@SuppressWarnings({"null"})
class RealtimeAfterCommitIntegrationTests extends RealtimeStompTestBase {

    @Autowired private AttemptService attemptService;
    @Autowired private AttemptSubmitService submitService;
    @Autowired private ExamSessionService examSessionService;
    @Autowired private PlatformTransactionManager txm;
    @Autowired private ObjectMapper objectMapper;

    private long sessionId;
    private long teacherId;
    private String teacherToken;
    private long studentId;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        sessionId = createTeacherOwnedOpenSession("ac-" + tag);
        teacherId = teacherIdForSession(sessionId);
        teacherToken = accessToken(teacherId, "ac-tch", List.of("TEACHER"));
        studentId = insertUserWithRole("ac-stu-" + tag, "STUDENT");
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentId + "," + school + ",'SC" + tag + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES ("
                + school + "," + sessionId + ",(SELECT id FROM student_profiles WHERE user_id=" + studentId + ")," + teacherId + ")");
    }

    // ============================ §8 HELD-UNCOMMITTED ============================

    @Test
    void startEmitsEventsOnlyAfterCommit() throws Exception {
        try (StompTestConnection teacher = connect(teacherToken);
             EventSink sink = subscribeTopic(teacher, sessionId, 2)) {
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);

            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            long attemptsBefore = attemptCount();
            try {
                Future<?> f = pool.submit(() -> new TransactionTemplate(txm).executeWithoutResult(status -> {
                    attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
                    held.countDown();
                    awaitRelease(release);
                }));
                assertThat(held.await(15, TimeUnit.SECONDS)).as("worker reached post-service; tx still held").isTrue();

                // While the outer tx is uncommitted: zero topic events (probe) + nothing delivered.
                outboundProbe.clear();
                assertNoOutbound(topicMessage(sessionId), 2);
                assertThat(sink.payloads).isEmpty();

                release.countDown();
                f.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            // After commit: exactly ATTEMPT_STARTED + ACTIVE_COUNT_CHANGED (DELIVERY_ORDER_UNSPECIFIED —
            // assert by type, not by index).
            assertThat(sink.latch.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(sink.payloads).hasSize(2);
            RealtimeEventEnvelope[] ev = parse(sink.payloads);
            assertThat(java.util.Arrays.stream(ev).filter(e -> RealtimeEventType.ATTEMPT_STARTED.name().equals(e.eventType())).count()).isEqualTo(1L);
            assertThat(java.util.Arrays.stream(ev).filter(e -> RealtimeEventType.ACTIVE_COUNT_CHANGED.name().equals(e.eventType())).count()).isEqualTo(1L);
            RealtimeEventEnvelope startedEv = java.util.Arrays.stream(ev).filter(e -> RealtimeEventType.ATTEMPT_STARTED.name().equals(e.eventType())).findFirst().orElseThrow();
            assertThat(startedEv.attemptId()).isNotNull();
            RealtimeEventEnvelope countEv = java.util.Arrays.stream(ev).filter(e -> RealtimeEventType.ACTIVE_COUNT_CHANGED.name().equals(e.eventType())).findFirst().orElseThrow();
            assertThat(countEv.attemptId()).isNull();
            assertThat(attemptCount()).isEqualTo(attemptsBefore + 1);
            assertUniqueEventIds(ev);
            assertNoLeak(sink.payloads);
        }
    }

    @Test
    void submitEmitsEventsOnlyAfterCommit() throws Exception {
        long attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
        try (StompTestConnection teacher = connect(teacherToken);
             EventSink sink = subscribeTopic(teacher, sessionId, 2)) {
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);

            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> f = pool.submit(() -> new TransactionTemplate(txm).executeWithoutResult(status -> {
                    submitService.submitAttempt(studentId, attemptId, new SubmitRequest("held-key-" + UUID.randomUUID()));
                    held.countDown();
                    awaitRelease(release);
                }));
                assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
                outboundProbe.clear();
                assertNoOutbound(topicMessage(sessionId), 2);
                assertThat(sink.payloads).isEmpty();
                release.countDown();
                f.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(sink.latch.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(sink.payloads).hasSize(2);
            RealtimeEventEnvelope[] ev = parse(sink.payloads);
            // DELIVERY_ORDER_UNSPECIFIED — assert by type count, not by index.
            assertThat(java.util.Arrays.stream(ev).filter(e -> RealtimeEventType.ATTEMPT_SUBMITTED.name().equals(e.eventType())).count()).isEqualTo(1L);
            assertThat(java.util.Arrays.stream(ev).filter(e -> RealtimeEventType.ACTIVE_COUNT_CHANGED.name().equals(e.eventType())).count()).isEqualTo(1L);
        }
    }

    @Test
    void sessionOpenEmitsEventOnlyAfterCommit() throws Exception {
        // A SCHEDULED session that the teacher owns (open transition SCHEDULED→OPEN).
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long ver = jdbc.queryForObject("SELECT exam_version_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long sched = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by) VALUES ("
                + school + "," + ver + ",(SELECT owner_teacher_id FROM exam_sessions WHERE id=" + sessionId + "),'SCH2','t','SCHEDULED','"
                + Instant.now().minusSeconds(3600) + "','" + Instant.now().plusSeconds(7200) + "',2," + teacherId + ")");
        try (StompTestConnection teacher = connect(teacherToken);
             EventSink sink = subscribeTopic(teacher, sched, 1)) {
            awaitAccepted(acceptedDestination(topic(sched)), 5);

            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> f = pool.submit(() -> new TransactionTemplate(txm).executeWithoutResult(status -> {
                    examSessionService.openSession(teacherId, sched);
                    held.countDown();
                    awaitRelease(release);
                }));
                assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
                outboundProbe.clear();
                assertNoOutbound(topicMessage(sched), 2);
                assertThat(sink.payloads).isEmpty();
                release.countDown();
                f.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertThat(sink.latch.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(sink.payloads).hasSize(1);
            assertThat(parse(sink.payloads)[0].eventType()).isEqualTo(RealtimeEventType.SESSION_OPENED.name());
        }
    }

    // ============================ §9 ROLLBACK ============================

    @Test
    void rollbackAttemptEmitsNoEventAndDoesNotPersist() throws Exception {
        try (StompTestConnection teacher = connect(teacherToken);
             EventSink sink = subscribeTopic(teacher, sessionId, 1)) {
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            outboundProbe.clear();
            long attemptsBefore = attemptCount();

            new TransactionTemplate(txm).executeWithoutResult(status -> {
                attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
                status.setRollbackOnly(); // service ran (transition + publish) THEN rollback
            });

            assertNoOutbound(topicMessage(sessionId), 2);
            assertThat(sink.payloads).isEmpty();
            assertThat(attemptCount()).as("DB rolled back — no attempt persisted").isEqualTo(attemptsBefore);
        }
    }

    @Test
    void rollbackSessionOpenEmitsNoEventAndDoesNotPersist() throws Exception {
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long ver = jdbc.queryForObject("SELECT exam_version_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long sched = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by) VALUES ("
                + school + "," + ver + ",(SELECT owner_teacher_id FROM exam_sessions WHERE id=" + sessionId + "),'RB','t','SCHEDULED','"
                + Instant.now().minusSeconds(3600) + "','" + Instant.now().plusSeconds(7200) + "',2," + teacherId + ")");
        try (StompTestConnection teacher = connect(teacherToken);
             EventSink sink = subscribeTopic(teacher, sched, 1)) {
            awaitAccepted(acceptedDestination(topic(sched)), 5);
            outboundProbe.clear();

            new TransactionTemplate(txm).executeWithoutResult(status -> {
                examSessionService.openSession(teacherId, sched);
                status.setRollbackOnly();
            });

            assertNoOutbound(topicMessage(sched), 2);
            assertThat(sink.payloads).isEmpty();
            String status = jdbc.queryForObject("SELECT status FROM exam_sessions WHERE id=" + sched, String.class);
            assertThat(status).isEqualTo("SCHEDULED"); // transition rolled back
        }
    }

    // ============================ §10 NO-OP ============================

    @Test
    void resumeStartEmitsNoEvent() throws Exception {
        attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId(); // committed
        try (StompTestConnection teacher = connect(teacherToken);
             EventSink sink = subscribeTopic(teacher, sessionId, 1)) {
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            outboundProbe.clear();
            attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)); // resume → no event
            assertNoOutbound(topicMessage(sessionId), 2);
            assertThat(sink.payloads).isEmpty();
        }
    }

    @Test
    void cachedRetrySubmitEmitsNoEvent() throws Exception {
        long attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
        submitService.submitAttempt(studentId, attemptId, new SubmitRequest("cache-key")); // first submit (events fire)
        try (StompTestConnection teacher = connect(teacherToken);
             EventSink sink = subscribeTopic(teacher, sessionId, 1)) {
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            outboundProbe.clear();
            submitService.submitAttempt(studentId, attemptId, new SubmitRequest("cache-key")); // cached retry → no event
            assertNoOutbound(topicMessage(sessionId), 2);
            assertThat(sink.payloads).isEmpty();
        }
    }

    // ============================ helpers ============================

    private void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("release latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting release", e);
        }
    }

    private static String topic(long sessionId) {
        return "/topic/exam-sessions/" + sessionId;
    }

    private static Predicate<OutboundMessageProbe.Captured> topicMessage(long sessionId) {
        String dest = topic(sessionId);
        return c -> c.command() == StompCommand.MESSAGE && dest.equals(c.destination());
    }

    private long attemptCount() {
        return jdbc.queryForObject("SELECT count(*) FROM attempts WHERE exam_session_id=?", Long.class, sessionId);
    }

    private RealtimeEventEnvelope[] parse(List<byte[]> payloads) {
        return payloads.stream().map(b -> {
            try {
                return objectMapper.readValue(b, RealtimeEventEnvelope.class);
            } catch (Exception e) {
                throw new RuntimeException("failed to parse realtime envelope", e);
            }
        }).toArray(RealtimeEventEnvelope[]::new);
    }

    private static void assertUniqueEventIds(RealtimeEventEnvelope[] events) {
        long distinct = java.util.Arrays.stream(events).map(RealtimeEventEnvelope::eventId).distinct().count();
        assertThat(distinct).as("every transition must carry a distinct eventId").isEqualTo(events.length);
    }

    private static void assertNoLeak(List<byte[]> payloads) {
        String joined = payloads.stream().map(p -> new String(p, java.nio.charset.StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertThat(joined).doesNotContain("answerPayload").doesNotContain("answerKey")
                .doesNotContain("isCorrect").doesNotContain("score").doesNotContain("grade")
                .doesNotContain("studentCode").doesNotContain("username").doesNotContain("userId")
                .doesNotContain("submissionIdempotencyKey").doesNotContain("clientInstanceId")
                .doesNotContain("schoolId");
    }

    /** Subscribes the topic + captures payloads into a sink. The {@link StompTestConnection} is owned by
     *  the caller's try-with-resources — this sink does NOT close it (avoids a double close → registry over-stop). */
    private EventSink subscribeTopic(StompTestConnection conn, long topicSessionId, int expected) {
        CopyOnWriteArrayList<byte[]> payloads = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(expected);
        conn.subscribe(topic(topicSessionId), new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) {
                payloads.add((byte[]) payload);
                latch.countDown();
            }
        });
        return new EventSink(payloads, latch);
    }

    private record EventSink(List<byte[]> payloads, CountDownLatch latch) implements AutoCloseable {
        @Override public void close() { /* no-op: the connection is owned by the outer try-with-resources */ }
    }
}
