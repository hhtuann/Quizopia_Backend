package com.hhtuann.backend.realtime;

import com.hhtuann.backend.exam.application.ExamSessionService;
import com.hhtuann.backend.realtime.event.RealtimeEventEnvelope;
import com.hhtuann.backend.realtime.event.RealtimeEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SESSION_OPENED / SESSION_CLOSED canonical-event matrix (Day 7 B1R4-B §6.3/§6.4/§10). Real STOMP +
 * parsed envelopes. Fires on a real transition only; no-op for idempotent / second-detail / bulk
 * lazy-close (the bulk no-event is the frozen D27 MVP exception, asserted here, NOT "fixed").
 */
class RealtimeSessionEventIntegrationTests extends RealtimeStompTestBase {

    @Autowired private ExamSessionService examSessionService;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

    private long sessionId;
    private long teacherId;
    private String teacherToken;
    private long school;
    private long versionId;
    private long ownerTeacherProfileId;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        sessionId = createTeacherOwnedOpenSession("se-" + tag);
        teacherId = teacherIdForSession(sessionId);
        teacherToken = accessToken(teacherId, "se-tch", List.of("TEACHER"));
        school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        versionId = jdbc.queryForObject("SELECT exam_version_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        ownerTeacherProfileId = jdbc.queryForObject("SELECT owner_teacher_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
    }

    @Test
    void scheduledToOpenEmitsSessionOpened() throws Exception {
        long sched = newScheduledSession();
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, sched, 1);
            awaitAccepted(acceptedDestination(topic(sched)), 5);
            examSessionService.openSession(teacherId, sched); // SCHEDULED → OPEN
            assertThat(cap.latch.await(10, TimeUnit.SECONDS)).isTrue();
            RealtimeEventEnvelope ev = parse(cap.payloads.get(0));
            assertThat(ev.eventType()).isEqualTo(RealtimeEventType.SESSION_OPENED.name());
            assertThat(ev.sessionId()).isEqualTo(sched);
            assertThat(ev.attemptId()).isNull();
            assertThat(ev.studentProfileId()).isNull();
        }
    }

    @Test
    void explicitCloseEmitsSessionClosed() throws Exception {
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, sessionId, 1); // sessionId is OPEN
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            examSessionService.closeSession(teacherId, sessionId); // OPEN → CLOSED
            assertThat(cap.latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(parse(cap.payloads.get(0)).eventType()).isEqualTo(RealtimeEventType.SESSION_CLOSED.name());
        }
    }

    @Test
    void detailLazyCloseEmitsSessionClosed() throws Exception {
        // Push the OPEN session past endsAt so getSessionDetail lazy-closes it.
        jdbc.update("UPDATE exam_sessions SET ends_at = now() WHERE id=" + sessionId);
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, sessionId, 1);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            examSessionService.getSessionDetail(teacherId, sessionId); // OPEN + past endsAt → CLOSED
            assertThat(cap.latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(parse(cap.payloads.get(0)).eventType()).isEqualTo(RealtimeEventType.SESSION_CLOSED.name());
        }
    }

    @Test
    void alreadyOpenEmitsNoEvent() throws Exception {
        long open = newOpenSession();
        try (StompTestConnection conn = connect(teacherToken)) {
            subscribe(conn, open, 1);
            awaitAccepted(acceptedDestination(topic(open)), 5);
            outboundProbe.clear();
            examSessionService.openSession(teacherId, open); // already OPEN → idempotent, no event
            assertNoOutbound(topicMessage(open), 2);
        }
    }

    @Test
    void alreadyClosedEmitsNoEvent() throws Exception {
        long closed = newClosedSession();
        try (StompTestConnection conn = connect(teacherToken)) {
            subscribe(conn, closed, 1);
            awaitAccepted(acceptedDestination(topic(closed)), 5);
            outboundProbe.clear();
            examSessionService.closeSession(teacherId, closed); // already CLOSED → idempotent, no event
            assertNoOutbound(topicMessage(closed), 2);
        }
    }

    @Test
    void secondDetailAfterLazyCloseEmitsNoEvent() throws Exception {
        jdbc.update("UPDATE exam_sessions SET ends_at = now() WHERE id=" + sessionId);
        examSessionService.getSessionDetail(teacherId, sessionId); // first detail → lazy-closes (event fired before subscribe)
        try (StompTestConnection conn = connect(teacherToken)) {
            subscribe(conn, sessionId, 1);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            outboundProbe.clear();
            examSessionService.getSessionDetail(teacherId, sessionId); // second detail → already CLOSED, no transition
            assertNoOutbound(topicMessage(sessionId), 2);
        }
    }

    @Test
    void bulkListLazyCloseEmitsNoEvent() throws Exception {
        // Frozen D27 MVP exception: the bulk UPDATE in listMySessions does NOT publish per-session events.
        jdbc.update("UPDATE exam_sessions SET ends_at = now() WHERE id=" + sessionId);
        try (StompTestConnection conn = connect(teacherToken)) {
            subscribe(conn, sessionId, 1);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            outboundProbe.clear();
            examSessionService.listMySessions(teacherId, null, null, null, 0, 20, null); // bulk lazy-close
            assertNoOutbound(topicMessage(sessionId), 2);
            // The session IS closed by the bulk update (DB), but NO realtime event.
            String status = jdbc.queryForObject("SELECT status FROM exam_sessions WHERE id=" + sessionId, String.class);
            assertThat(status).isEqualTo("CLOSED");
        }
    }

    // --- fixtures ---

    private long newScheduledSession() {
        return ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by) VALUES ("
                + school + "," + versionId + "," + ownerTeacherProfileId + ",'SCH','t','SCHEDULED','"
                + Instant.now().minusSeconds(3600) + "','" + Instant.now().plusSeconds(7200) + "',2," + teacherId + ")");
    }

    private long newOpenSession() {
        Instant n = Instant.now();
        return ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + school + "," + versionId + "," + ownerTeacherProfileId + ",'OPN','t','OPEN','"
                + n.minusSeconds(3600) + "','" + n.plusSeconds(7200) + "',2," + teacherId + ",'" + n.minusSeconds(3600) + "')");
    }

    private long newClosedSession() {
        Instant n = Instant.now();
        return ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, closed_at) VALUES ("
                + school + "," + versionId + "," + ownerTeacherProfileId + ",'CLD','t','CLOSED','"
                + n.minusSeconds(7200) + "','" + n.minusSeconds(3600) + "',2," + teacherId + ",'" + n.minusSeconds(7200) + "','" + n.minusSeconds(3600) + "')");
    }

    // --- helpers ---

    private static String topic(long sid) { return "/topic/exam-sessions/" + sid; }

    private static Predicate<OutboundMessageProbe.Captured> topicMessage(long sid) {
        String d = topic(sid);
        return c -> c.command() == StompCommand.MESSAGE && d.equals(c.destination());
    }

    private RealtimeEventEnvelope parse(byte[] payload) {
        try {
            return objectMapper.readValue(payload, RealtimeEventEnvelope.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Capture subscribe(StompTestConnection conn, long sid, int expected) {
        CopyOnWriteArrayList<byte[]> payloads = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(expected);
        conn.subscribe(topic(sid), new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) { payloads.add((byte[]) payload); latch.countDown(); }
        });
        return new Capture(payloads, latch);
    }

    private record Capture(List<byte[]> payloads, CountDownLatch latch) {}
}
