package com.hhtuann.backend.realtime;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
import com.hhtuann.backend.realtime.event.RealtimeEventEnvelope;
import com.hhtuann.backend.realtime.event.RealtimeEventType;
import com.hhtuann.backend.realtime.support.RecordingRealtimeActiveCountService;
import com.hhtuann.backend.realtime.support.RealtimeTestSupportConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
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
 * Active-count closure (Day 7 B1R4-B §11-§13). The {@link RecordingRealtimeActiveCountService}
 * decorator ({@code @Primary} via {@link RealtimeTestSupportConfig}) counts {@code countActiveAttempts}
 * invocations and records the live transaction context, proving:
 * <ul>
 *   <li>§11 correctness: start → activeCount N+1; submit → N-1; 2 active → submit one → 1;</li>
 *   <li>§12 fresh REQUIRES_NEW: each call runs in an active, read-only transaction (a self-invocation
 *       would have NO active transaction; the write tx already committed before AFTER_COMMIT fires);</li>
 *   <li>§13 query matrix: exactly one count invocation per committed attempt transition; zero for
 *       resume / cached retry / session events / rollback.</li>
 * </ul>
 */
@Import(RealtimeTestSupportConfig.class)
class RealtimeActiveCountIntegrationTests extends RealtimeStompTestBase {

    @Autowired private AttemptService attemptService;
    @Autowired private AttemptSubmitService submitService;
    @Autowired private com.hhtuann.backend.exam.application.ExamSessionService examSessionService;
    @Autowired private RecordingRealtimeActiveCountService activeCount;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txm;

    private org.springframework.transaction.support.TransactionTemplate tx() {
        return new org.springframework.transaction.support.TransactionTemplate(txm);
    }

    private long sessionId;
    private long teacherId;
    private String teacherToken;
    private long studentId;
    private long studentId2;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        sessionId = createTeacherOwnedOpenSession("cnt-" + tag);
        teacherId = teacherIdForSession(sessionId);
        teacherToken = accessToken(teacherId, "cnt-tch", List.of("TEACHER"));
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        studentId = addStudentParticipant("cnt-s1-" + tag, school);
        studentId2 = addStudentParticipant("cnt-s2-" + tag, school);
        activeCount.reset();
    }

    @Test
    void startIncrementsActiveCountAndRunsOneQueryInNewReadOnlyTx() throws Exception {
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, sessionId, 2);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
            assertThat(cap.latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertNoExtraFrame(cap, 2);

            RealtimeEventEnvelope countEv = findExactlyOne(parse(cap.payloads), RealtimeEventType.ACTIVE_COUNT_CHANGED);
            assertThat(countEv.activeCount()).as("start → N+1").isEqualTo(1L);
            assertThat(activeCount.invocationCount()).as("exactly one count query per committed start").isEqualTo(1);
            RecordingRealtimeActiveCountService.TxContext ctx = activeCount.recordedContexts().get(0);
            assertThat(ctx.active()).as("REQUIRES_NEW tx active (not self-invocation)").isTrue();
            assertThat(ctx.readOnly()).as("fresh tx is read-only").isTrue();
            assertThat(ctx.name()).as("tx name is the countActiveAttempts method").isNotNull();
        }
    }

    @Test
    void submitDecrementsActiveCount() throws Exception {
        activeCount.reset();
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, sessionId, 4); // start(2) + submit(2) — subscribe BEFORE both
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            long attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
            activeCount.reset(); // clear the start's count-query so we count only the submit's
            submitService.submitAttempt(studentId, attemptId, new SubmitRequest("dec-" + UUID.randomUUID()));
            assertThat(cap.latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertNoExtraFrame(cap, 4);

            // DELIVERY_ORDER_UNSPECIFIED — find by type, not by index.
            findExactlyOne(parse(cap.payloads), RealtimeEventType.ATTEMPT_SUBMITTED);
            RealtimeEventEnvelope countEv = findExactlyOne(parse(cap.payloads), RealtimeEventType.ACTIVE_COUNT_CHANGED,
                    e -> e.activeCount() != null && e.activeCount() == 0);
            assertThat(countEv.activeCount()).as("submit → N-1 (does not count SUBMITTED)").isZero();
            assertThat(activeCount.invocationCount()).as("exactly one count query per committed submit").isEqualTo(1);
        }
    }

    @Test
    void twoActiveSubmitOneGoesToOne() throws Exception {
        attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
        long attempt2 = attemptService.startAttempt(studentId2, sessionId, new StartAttemptRequest(null)).attemptId();
        activeCount.reset();
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, sessionId, 2);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            submitService.submitAttempt(studentId2, attempt2, new SubmitRequest("two-" + UUID.randomUUID()));
            assertThat(cap.latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertNoExtraFrame(cap, 2);
            RealtimeEventEnvelope countEv = findExactlyOne(parse(cap.payloads), RealtimeEventType.ACTIVE_COUNT_CHANGED);
            assertThat(countEv.activeCount()).as("2 active → submit one → 1").isEqualTo(1L);
        }
    }

    @Test
    void resumeRunsNoCountQuery() throws Exception {
        attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
        activeCount.reset();
        try (StompTestConnection conn = connect(teacherToken)) {
            subscribe(conn, sessionId, 1);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)); // resume
            assertThat(activeCount.invocationCount()).as("resume → 0 count queries").isZero();
        }
    }

    @Test
    void cachedRetryRunsNoCountQuery() throws Exception {
        long attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
        submitService.submitAttempt(studentId, attemptId, new SubmitRequest("cache"));
        activeCount.reset();
        try (StompTestConnection conn = connect(teacherToken)) {
            subscribe(conn, sessionId, 1);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            submitService.submitAttempt(studentId, attemptId, new SubmitRequest("cache")); // cached retry
            assertThat(activeCount.invocationCount()).as("cached retry → 0 count queries").isZero();
        }
    }

    @Test
    void activeCountBeanIsAopProxyAndQueryIsSingleAggregateOnAttempts() throws Exception {
        // §7 — the @Primary decorator is a Spring AOP proxy (inherited @Transactional honored).
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(activeCount))
                .as("RealtimeActiveCountService must be an AOP proxy").isTrue();
        // §6 — capture the ACTUAL SQL (Hibernate org.hibernate.SQL at DEBUG).
        ch.qos.logback.classic.Logger sqlLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("org.hibernate.SQL");
        ch.qos.logback.classic.Level original = sqlLogger.getLevel();
        sqlLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, sessionId, 2);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            activeCount.reset();
            try (com.hhtuann.backend.realtime.support.InMemoryLogAppender app =
                         com.hhtuann.backend.realtime.support.InMemoryLogAppender.attach("org.hibernate.SQL")) {
                attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
                assertThat(cap.latch.await(20, TimeUnit.SECONDS)).isTrue();
                String sql = app.joined().toLowerCase().replaceAll("\\s+", " ");
                assertThat(sql).as("aggregate count over attempts").contains("count").contains("from attempts")
                        .contains("exam_session_id").contains("status");
                assertThat(sql).as("no entity-list load / no grade or answer joins")
                        .doesNotContain("join grades").doesNotContain("join grade_items")
                        .doesNotContain("join attempt_answers");
            }
        } finally {
            sqlLogger.setLevel(original);
        }
    }

    @Test
    void writeAndCountUseDifferentTransactionResources() throws Exception {
        // §4 — direct resource-identity comparison: the write tx's ConnectionHolder != the count tx's.
        javax.sql.DataSource ds = dataSource;
        try (StompTestConnection conn = connect(teacherToken)) {
            Capture cap = subscribe(conn, sessionId, 2);
            awaitAccepted(acceptedDestination(topic(sessionId)), 5);
            activeCount.reset();
            int[] writeIdentity = {0};
            java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                pool.submit(() -> tx().executeWithoutResult(status -> {
                    attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
                    Object res = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(ds);
                    writeIdentity[0] = res != null ? System.identityHashCode(res) : 0;
                    held.countDown();
                    try { if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError("interrupted", e); }
                }));
                assertThat(held.await(15, TimeUnit.SECONDS)).as("write worker held tx + captured resource").isTrue();
                release.countDown();
            } finally { pool.shutdown(); pool.awaitTermination(15, TimeUnit.SECONDS); }
            assertThat(cap.latch.await(20, TimeUnit.SECONDS)).isTrue();

            assertThat(activeCount.invocationCount()).isEqualTo(1);
            RecordingRealtimeActiveCountService.TxContext ctx = activeCount.recordedContexts().get(0);
            assertThat(ctx.active()).as("count tx active").isTrue();
            assertThat(ctx.readOnly()).as("count tx read-only").isTrue();
            assertThat(writeIdentity[0]).as("write tx resource non-null").isNotZero();
            assertThat(ctx.resourceIdentity()).as("count tx resource non-null").isNotZero();
            assertThat(ctx.resourceIdentity()).as("write/count ConnectionHolders differ (fresh REQUIRES_NEW)")
                    .isNotEqualTo(writeIdentity[0]);
        }
    }

    @Test
    void resumeRunsZeroActualActiveCountSql() throws Exception {
        // §5 — actual captured SQL proves 0 matching active-count statements for a no-op resume.
        attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
        activeCount.reset();
        ch.qos.logback.classic.Logger sqlLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("org.hibernate.SQL");
        ch.qos.logback.classic.Level original = sqlLogger.getLevel();
        sqlLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            try (com.hhtuann.backend.realtime.support.InMemoryLogAppender app =
                         com.hhtuann.backend.realtime.support.InMemoryLogAppender.attach("org.hibernate.SQL")) {
                attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)); // resume
                assertThat(activeCount.invocationCount()).as("resume → 0 count queries").isZero();
                assertThat(matchingActiveCountStatements(app.formattedMessages()))
                        .as("resume → 0 actual active-count SQL statements").isZero();
            }
        } finally { sqlLogger.setLevel(original); }
    }

    /** Counts SQL statements (one per Hibernate log event) matching the active-count aggregate pattern. */
    private static long matchingActiveCountStatements(java.util.List<String> messages) {
        return messages.stream()
                .map(s -> s.toLowerCase().replaceAll("\\s+", " "))
                .filter(s -> s.contains("count") && s.contains("attempts")
                        && s.contains("exam_session_id") && s.contains("status"))
                .count();
    }

    // ============================ §5 ACTUAL SQL ZERO-MATRIX (B1R4-B1F2) ============================
    // Every scenario proven by ACTUAL captured Hibernate SQL (org.hibernate.SQL at DEBUG), not invocationCount.
    // The count query fires AFTER_COMMIT synchronously — no STOMP subscriber needed; the SQL is logged
    // regardless of whether a client is listening. Bind values don't appear in Hibernate SQL logs; the
    // status=IN_PROGRESS bind is proven by the repository method countByExamSessionIdAndStatus(…, AttemptStatus.IN_PROGRESS).

    @Test
    void actualSqlSubmitCommitIsOne() {
        long attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
        activeCount.reset();
        long n = captureActiveCountSqlDuring(() ->
                submitService.submitAttempt(studentId, attemptId, new SubmitRequest("sql-submit-" + UUID.randomUUID())));
        assertThat(n).as("first submit commit → exactly 1 matching active-count SQL").isEqualTo(1L);
    }

    @Test
    void actualSqlCachedRetryIsZero() {
        long attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
        submitService.submitAttempt(studentId, attemptId, new SubmitRequest("sql-cache"));
        activeCount.reset();
        long n = captureActiveCountSqlDuring(() ->
                submitService.submitAttempt(studentId, attemptId, new SubmitRequest("sql-cache"))); // cached retry
        assertThat(n).as("cached submit retry → 0 matching active-count SQL").isZero();
    }

    @Test
    void actualSqlRollbackAttemptIsZero() {
        activeCount.reset();
        long n = captureActiveCountSqlDuring(() -> tx().executeWithoutResult(status -> {
            attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null));
            status.setRollbackOnly();
        }));
        assertThat(n).as("rollback attempt → 0 matching active-count SQL").isZero();
    }

    @Test
    void actualSqlSessionOpenIsZero() {
        long sched = newScheduledSession();
        long n = captureActiveCountSqlDuring(() -> examSessionService.openSession(teacherId, sched));
        assertThat(n).as("session open → 0 matching active-count SQL").isZero();
    }

    @Test
    void actualSqlSessionCloseIsZero() {
        long n = captureActiveCountSqlDuring(() -> examSessionService.closeSession(teacherId, sessionId));
        assertThat(n).as("session close → 0 matching active-count SQL").isZero();
    }

    @Test
    void actualSqlBulkLazyCloseIsZero() {
        jdbc.update("UPDATE exam_sessions SET ends_at = now() WHERE id=" + sessionId);
        long n = captureActiveCountSqlDuring(() ->
                examSessionService.listMySessions(teacherId, null, null, null, 0, 20, null));
        assertThat(n).as("bulk lazy-close → 0 matching active-count SQL").isZero();
    }

    /** Captures Hibernate SQL during {@code action} (logger at DEBUG, fresh appender attached AFTER fixture
     *  setup) and returns the number of statements matching the active-count aggregate pattern. */
    private long captureActiveCountSqlDuring(Runnable action) {
        ch.qos.logback.classic.Logger sqlLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("org.hibernate.SQL");
        ch.qos.logback.classic.Level original = sqlLogger.getLevel();
        sqlLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try (com.hhtuann.backend.realtime.support.InMemoryLogAppender app =
                     com.hhtuann.backend.realtime.support.InMemoryLogAppender.attach("org.hibernate.SQL")) {
            action.run();
            return matchingActiveCountStatements(app.formattedMessages());
        } finally {
            sqlLogger.setLevel(original);
        }
    }

    private long newScheduledSession() {
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long ver = jdbc.queryForObject("SELECT exam_version_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long owner = jdbc.queryForObject("SELECT owner_teacher_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        return ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by) VALUES ("
                + school + "," + ver + "," + owner + ",'SQLSCH','t','SCHEDULED','"
                + java.time.Instant.now().minusSeconds(3600) + "','" + java.time.Instant.now().plusSeconds(7200) + "',2," + teacherId + ")");
    }

    // --- DELIVERY_ORDER_UNSPECIFIED helpers (B1R4-B2 §1) ---

    /** Finds exactly one event of the given type; fails if zero or more than one. */
    private static RealtimeEventEnvelope findExactlyOne(RealtimeEventEnvelope[] events, RealtimeEventType type) {
        return findExactlyOne(events, type, e -> true);
    }

    /** Finds exactly one event of the given type matching an additional predicate. */
    private static RealtimeEventEnvelope findExactlyOne(RealtimeEventEnvelope[] events, RealtimeEventType type,
                                                        java.util.function.Predicate<RealtimeEventEnvelope> extra) {
        List<RealtimeEventEnvelope> matches = java.util.Arrays.stream(events)
                .filter(e -> type.name().equals(e.eventType()) && extra.test(e)).toList();
        assertThat(matches).as("exactly one %s event matching".formatted(type)).hasSize(1);
        return matches.get(0);
    }

    /** After the expected latch count is received, asserts no extra frame arrives in a bounded window. */
    private static void assertNoExtraFrame(Capture cap, int expectedSize) throws InterruptedException {
        assertThat(cap.payloads).as("exact expected frame count").hasSize(expectedSize);
        // A fresh latch that would be counted down if any delayed duplicate arrives.
        CountDownLatch extra = new CountDownLatch(1);
        // Wait a bounded window — if no extra frame, the latch times out (success).
        assertThat(extra.await(2, TimeUnit.SECONDS))
                .as("no delayed duplicate frame within bounded window").isFalse();
    }

    // --- helpers ---

    private long addStudentParticipant(String tag, long school) {
        long uid = insertUserWithRole(tag, "STUDENT");
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + uid + "," + school + ",'SC" + tag + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES ("
                + school + "," + sessionId + ",(SELECT id FROM student_profiles WHERE user_id=" + uid + ")," + teacherId + ")");
        return uid;
    }

    private static String topic(long sid) { return "/topic/exam-sessions/" + sid; }

    private static Predicate<OutboundMessageProbe.Captured> topicMessage(long sid) {
        String d = topic(sid);
        return c -> c.command() == StompCommand.MESSAGE && d.equals(c.destination());
    }

    private RealtimeEventEnvelope[] parse(List<byte[]> payloads) {
        return payloads.stream().map(b -> {
            try { return objectMapper.readValue(b, RealtimeEventEnvelope.class); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).toArray(RealtimeEventEnvelope[]::new);
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
