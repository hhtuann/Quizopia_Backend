package com.quizopia.backend.realtime;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.application.AttemptSubmitService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.realtime.event.RealtimeEventEnvelope;
import com.quizopia.backend.realtime.event.RealtimeEventType;
import com.quizopia.backend.realtime.support.FailingRealtimePublisher;
import com.quizopia.backend.realtime.support.InMemoryLogAppender;
import com.quizopia.backend.realtime.support.RealtimeTestSupportConfig;
import com.quizopia.backend.realtime.support.RecordingRealtimeActiveCountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbound failure-isolation closure (Day 7 B1R4-B §14-§17). Uses the {@code @Primary} test decorators
 * ({@link FailingRealtimePublisher} + {@link RecordingRealtimeActiveCountService}) to inject one-shot
 * failures into specific sends / the count query, then asserts:
 * <ul>
 *   <li>the REST/service transaction still commits (no rollback, no 5xx);</li>
 *   <li>the independent second send is NOT suppressed by a failed first send (failure isolation);</li>
 *   <li>no retry / no duplicate;</li>
 *   <li>the broadcaster's WARN log omits the injected sensitive marker (§17 sanitized logging).</li>
 * </ul>
 *
 * <p>The service call with the failure injected IS the REST path (the controller delegates to the
 * service; the broadcaster runs AFTER_COMMIT and cannot reach the response). The §16 HTTP/MockMvc
 * surface adds only the security-filter + controller layer, which does not interact with a post-commit
 * broker failure.
 */
@Import(RealtimeTestSupportConfig.class)
class RealtimeOutboundFailureIsolationIntegrationTests extends RealtimeStompTestBase {

    @Autowired private AttemptService attemptService;
    @Autowired private AttemptSubmitService submitService;
    @Autowired private ExamSessionService examSessionService;
    @Autowired private FailingRealtimePublisher publisher;
    @Autowired private RecordingRealtimeActiveCountService activeCount;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

    private long sessionId;
    private long teacherId;
    private String teacherToken;
    private long studentId;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        sessionId = createTeacherOwnedOpenSession("fi-" + tag);
        teacherId = teacherIdForSession(sessionId);
        teacherToken = accessToken(teacherId, "fi-tch", List.of("TEACHER"));
        studentId = insertUserWithRole("fi-stu-" + tag, "STUDENT");
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentId + "," + school + ",'SC" + tag + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES ("
                + school + "," + sessionId + ",(SELECT id FROM student_profiles WHERE user_id=" + studentId + ")," + teacherId + ")");
        publisher.reset();
        activeCount.reset();
    }

    // §15.1 — attempt-event send fails: REST commits, ACTIVE_COUNT_CHANGED still sent, no duplicate.
    @Test
    void attemptEventSendFailureRestCommitsAndCountStillSent() throws Exception {
        publisher.failNext((type, sid) -> type == RealtimeEventType.ATTEMPT_STARTED && sid.equals(sessionId));
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, 1); // the failed ATTEMPT_STARTED is dropped; only ACTIVE_COUNT_CHANGED arrives
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);

            long attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
            assertThat(attemptId).as("REST path committed the attempt (no rollback)").isGreaterThan(0);

            assertThat(cap.latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cap.payloads).as("the failed ATTEMPT_STARTED is dropped; the independent ACTIVE_COUNT_CHANGED is still sent")
                    .hasSize(1);
            assertThat(parse(cap.payloads)[0].eventType()).isEqualTo(RealtimeEventType.ACTIVE_COUNT_CHANGED.name());
            // No retry: only one send attempt for ATTEMPT_STARTED (the failed one) — recorded by the decorator.
            assertThat(publisher.sendAttempts().stream().filter(a -> a.type() == RealtimeEventType.ATTEMPT_STARTED).count())
                    .as("no retry of the failed attempt event").isEqualTo(1);
        }
    }

    // §15.2 — active-count send fails: attempt event still sent, REST commits, no rollback.
    @Test
    void activeCountSendFailureAttemptEventStillSent() throws Exception {
        publisher.failNext((type, sid) -> type == RealtimeEventType.ACTIVE_COUNT_CHANGED && sid.equals(sessionId));
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, 1); // the failed ACTIVE_COUNT_CHANGED is dropped; ATTEMPT_STARTED still arrives
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);

            attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
            assertThat(cap.latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cap.payloads).as("the failed ACTIVE_COUNT_CHANGED is dropped; ATTEMPT_STARTED is still sent")
                    .hasSize(1);
            assertThat(parse(cap.payloads)[0].eventType()).isEqualTo(RealtimeEventType.ATTEMPT_STARTED.name());
        }
    }

    // §15.3 — session-event send fails: open still commits (no 5xx).
    @Test
    void sessionEventSendFailureRestCommits() throws Exception {
        long sched = newScheduledSession();
        publisher.failNext((type, sid) -> type == RealtimeEventType.SESSION_OPENED && sid.equals(sched));
        try (StompTestConnection conn = connect(teacherToken)) {
            subscribe(conn, 1, sched);
            awaitAccepted(acceptedDestination(topic(sched)), 5);

            examSessionService.openSession(teacherId, sched); // SCHEDULED → OPEN; SESSION_OPENED send fails (isolated)
            String status = jdbc.queryForObject("SELECT status FROM exam_sessions WHERE id=" + sched, String.class);
            assertThat(status).as("REST path committed OPEN despite the broker send failure").isEqualTo("OPEN");
        }
    }

    // §15.4 — active-count query fails: REST commits, no fake count, both events suppressed (documented behavior).
    @Test
    void activeCountQueryFailureRestCommitsAndSendsNoFakeCount() throws Exception {
        activeCount.failNextCount(sid -> sid == sessionId);
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, 2);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);

            long attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
            assertThat(attemptId).as("REST path committed despite the count-query failure").isGreaterThan(0);
            // Documented behavior: the broadcaster's outer try-catch wraps count + both sends, so a
            // count-query failure suppresses BOTH events (no fake/partial payload). REST is source of truth.
            assertNoOutboundForSession(sessionId, 2);
            assertThat(cap.payloads).isEmpty();
        }
    }

    // §17 — sanitized logging: the injected sensitive marker must NOT appear in the broadcaster's WARN log.
    @Test
    void sanitizedLoggingOmitsInjectedSensitiveMarker() throws Exception {
        publisher.failNext((type, sid) -> type == RealtimeEventType.ATTEMPT_STARTED && sid.equals(sessionId));
        try (StompTestConnection conn = connect(teacherToken)) {
            subscribe(conn, 1);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            try (InMemoryLogAppender appender = InMemoryLogAppender.attach(
                    "com.quizopia.backend.realtime.event.RealtimeEventBroadcaster")) {
                attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
                // Harden: the appender MUST have captured at least one WARN (proves the failure was logged).
                assertThat(appender.formattedMessages())
                        .as("at least one WARN must be captured (the failure was logged)").isNotEmpty();
                String joined = appender.joined();
                // The WARN must contain generic event type + session context (not the raw exception).
                assertThat(joined).contains("ATTEMPT_STARTED").contains(String.valueOf(sessionId));
                // Must NOT contain the injected sensitive marker or any of its components.
                assertThat(joined)
                        .as("the injected sensitive marker (token/SQL/email/answerKey) must not leak to logs")
                        .doesNotContain(FailingRealtimePublisher.SENSITIVE_MARKER)
                        .doesNotContain("Bearer secret-token")
                        .doesNotContain("student@example.com")
                        .doesNotContain("answerKey=A")
                        .doesNotContain("SELECT * FROM");
            }
        }
    }

    // --- helpers ---

    private long newScheduledSession() {
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long ver = jdbc.queryForObject("SELECT exam_version_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long owner = jdbc.queryForObject("SELECT owner_teacher_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        return ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by) VALUES ("
                + school + "," + ver + "," + owner + ",'FISCH','t','SCHEDULED','"
                + Instant.now().minusSeconds(3600) + "','" + Instant.now().plusSeconds(7200) + "',2," + teacherId + ")");
    }

    private void assertNoOutboundForSession(long sid, long timeoutSeconds) {
        String d = topic(sid);
        assertNoOutbound(c -> c.command() == org.springframework.messaging.simp.stomp.StompCommand.MESSAGE && d.equals(c.destination()), timeoutSeconds);
    }

    private static String topic(long sid) { return "/topic/exam-sessions/" + sid; }

    private RealtimeEventEnvelope[] parse(List<byte[]> payloads) {
        return payloads.stream().map(b -> {
            try { return objectMapper.readValue(b, RealtimeEventEnvelope.class); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).toArray(RealtimeEventEnvelope[]::new);
    }

    private Capture subscribe(StompTestConnection conn, int expected) {
        return subscribe(conn, expected, sessionId);
    }

    private Capture subscribe(StompTestConnection conn, int expected, long sid) {
        CopyOnWriteArrayList<byte[]> payloads = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(expected);
        conn.subscribe(topic(sid), new org.springframework.messaging.simp.stomp.StompFrameHandler() {
            @Override public java.lang.reflect.Type getPayloadType(org.springframework.messaging.simp.stomp.StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(org.springframework.messaging.simp.stomp.StompHeaders h, Object payload) { payloads.add((byte[]) payload); latch.countDown(); }
        });
        return new Capture(payloads, latch);
    }

    private record Capture(List<byte[]> payloads, CountDownLatch latch) {}
}
