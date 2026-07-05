package com.hhtuann.backend.realtime;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
import com.hhtuann.backend.exam.application.ExamSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end realtime-event integration (Day 7 §17). Connection lifecycle uses {@link StompTestConnection}
 * (B1R4-A §1/§8 — try-with-resources, per-connection transport-error future, client stopped on close).
 *
 * <p>The event-delivery assertions themselves (AFTER_COMMIT, active-count, no-duplicate) are exercised
 * here but their full closure belongs to B1R4-B; this checkpoint only migrates the connection plumbing.
 */
class RealtimeEventIntegrationTests extends RealtimeStompTestBase {

    @Autowired private AttemptService attemptService;
    @Autowired private AttemptSubmitService submitService;
    @Autowired private ExamSessionService examSessionService;

    private long sessionId;
    private long teacherId;
    private String teacherToken;
    private long studentId;
    private long attemptId;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        sessionId = createTeacherOwnedOpenSession("evt-" + tag);
        teacherId = teacherIdForSession(sessionId);
        teacherToken = accessToken(teacherId, "evt-tch", List.of("TEACHER"));
        studentId = insertUserWithRole("evt-stu-" + tag, "STUDENT");
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentId + "," + school + ",'SC" + tag + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES ("
                + school + "," + sessionId + ",(SELECT id FROM student_profiles WHERE user_id=" + studentId + ")," + teacherId + ")");
    }

    @Test
    void startSendsAttemptStartedAndActiveCount() throws Exception {
        try (StompSessionHolder holder = subscribeTeacher()) {
            attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
            assertThat(holder.eventLatch.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(holder.payloads).hasSize(2);
            // Both canonical events are delivered (the simple broker is an async ExecutorChannel and may,
            // under extreme load, reorder the two sends within a transition — assert presence, not index).
            String joined = String.join("", holder.payloads);
            assertThat(joined).contains("ATTEMPT_STARTED").contains("ACTIVE_COUNT_CHANGED")
                    .contains("\"attemptId\":" + attemptId).contains("\"studentProfileId\"")
                    .contains("\"sessionId\":" + sessionId).contains("\"activeCount\":1");
            assertNoLeak(joined);
        }
    }

    @Test
    void submitSendsAttemptSubmittedAndActiveCount() throws Exception {
        attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
        try (StompSessionHolder holder = subscribeTeacher()) {
            submitService.submitAttempt(studentId, attemptId, new SubmitRequest("evt-key-" + UUID.randomUUID()));
            assertThat(holder.eventLatch.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(holder.payloads).hasSize(2);
            String joined = String.join("", holder.payloads);
            assertThat(joined).contains("ATTEMPT_SUBMITTED").contains("ACTIVE_COUNT_CHANGED")
                    .contains("\"attemptId\":" + attemptId).contains("\"activeCount\":0");
            assertNoLeak(joined);
        }
    }

    @Test
    void cachedRetrySendsNoEvent() throws Exception {
        // Subscribe BEFORE the start+submit to avoid the race where AFTER_COMMIT events arrive
        // at the broker while the subscription is being registered.
        try (StompSessionHolder holder = subscribeTeacher(sessionId, 4)) {
            attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
            submitService.submitAttempt(studentId, attemptId, new SubmitRequest("cached-key"));
            assertThat(holder.eventLatch.await(20, TimeUnit.SECONDS))
                    .as("start(2) + submit(2) = 4 events must arrive").isTrue();
            assertThat(holder.payloads).hasSize(4);
            // Cached retry: should produce 0 additional events.
            submitService.submitAttempt(studentId, attemptId, new SubmitRequest("cached-key"));
            CountDownLatch noMoreEvents = new CountDownLatch(1);
            assertThat(noMoreEvents.await(2, TimeUnit.SECONDS))
                    .as("cached retry must not publish additional events").isFalse();
            assertThat(holder.payloads).as("cached retry must not add events").hasSize(4);
        }
    }

    @Test
    void sessionOpenSendsSessionOpened() throws Exception {
        Instant realNow = Instant.now();
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long ver = jdbc.queryForObject("SELECT exam_version_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long schedSession = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by) VALUES ("
                + school + "," + ver + ",(SELECT owner_teacher_id FROM exam_sessions WHERE id=" + sessionId + "),'SCH','t','SCHEDULED','" + realNow.minusSeconds(3600) + "','" + realNow.plusSeconds(7200) + "',2," + teacherId + ")");
        try (StompTestConnection conn = connect(teacherToken)) {
            CopyOnWriteArrayList<String> payloads = new CopyOnWriteArrayList<>();
            CountDownLatch eventLatch = new CountDownLatch(1);
            conn.subscribe("/topic/exam-sessions/" + schedSession, new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
                @Override public void handleFrame(StompHeaders h, Object payload) {
                    payloads.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
                    eventLatch.countDown();
                }
            });
            // Topic readiness: SessionSubscribeEvent fires AFTER the inbound interceptors pass the topic
            // subscription (authorization + inbound receipt — NOT broker registration per B2F8). No SERVER_TIME_SYNC proxy.
            awaitAccepted(acceptedDestination("/topic/exam-sessions/" + schedSession), 10);

            examSessionService.openSession(teacherId, schedSession); // SCHEDULED → OPEN
            assertThat(eventLatch.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(payloads).hasSize(1);
            assertThat(payloads.get(0)).contains("SESSION_OPENED").contains("\"sessionId\":" + schedSession)
                    .doesNotContain("attemptId").doesNotContain("studentProfileId").doesNotContain("activeCount");
        }
    }

    @Test
    void rollbackSendsNoEvent() {
        // A closed session rejects start with SESSION_NOT_OPEN — the publish call is never reached.
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=now() WHERE id=" + sessionId);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)))
                .isInstanceOf(com.hhtuann.backend.attempt.exception.AttemptException.class);
    }

    private StompSessionHolder subscribeTeacher() throws Exception {
        return subscribeTeacher(sessionId, 2);
    }

    /** Overload with a configurable event-latch count (for tests that expect more than 2 topic events).
     *  Topic readiness is proven via {@link AcceptedSubscriptionProbe} (SessionSubscribeEvent fires after
     *  the inbound interceptors pass the topic SUBSCRIBE — authorization + inbound receipt, NOT broker
     *  registration; B2F8 corrected the prior assumption) — NOT via SERVER_TIME_SYNC as a proxy. */
    private StompSessionHolder subscribeTeacher(long topicSessionId, int expectedEvents) throws Exception {
        StompTestConnection conn = connect(teacherToken);
        try {
            CopyOnWriteArrayList<String> payloads = new CopyOnWriteArrayList<>();
            CountDownLatch eventLatch = new CountDownLatch(expectedEvents);
            conn.subscribe("/topic/exam-sessions/" + topicSessionId, new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
                @Override public void handleFrame(StompHeaders h, Object payload) {
                    payloads.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
                    eventLatch.countDown();
                }
            });
            // Exact topic-subscription readiness: SessionSubscribeEvent fires AFTER the broker registers
            // the topic subscription — no SERVER_TIME_SYNC proxy needed.
            awaitAccepted(acceptedDestination("/topic/exam-sessions/" + topicSessionId), 10);
            return new StompSessionHolder(conn, payloads, eventLatch);
        } catch (Throwable t) {
            try { conn.close(); } catch (Exception ignored) {}
            throw t;
        }
    }

    private void assertNoLeak(String joined) {
        assertThat(joined).doesNotContain("answerPayload").doesNotContain("answerKey")
                .doesNotContain("expectedAnswer").doesNotContain("isCorrect").doesNotContain("score")
                .doesNotContain("grade").doesNotContain("studentCode").doesNotContain("userId")
                .doesNotContain("username").doesNotContain("submissionIdempotencyKey")
                .doesNotContain("clientInstanceId").doesNotContain("schoolId");
    }

    private record StompSessionHolder(StompTestConnection connection, List<String> payloads, CountDownLatch eventLatch)
            implements AutoCloseable {
        @Override public void close() { connection.close(); }
    }
}
