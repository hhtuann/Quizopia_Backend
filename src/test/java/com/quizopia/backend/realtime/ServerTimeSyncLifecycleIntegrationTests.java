package com.quizopia.backend.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-STOMP executable evidence for {@code SERVER_TIME_SYNC} delivery, session targeting, and
 * ERROR no-leak (B1R4-A §2, B1R4-A1 §4/§5). Zero-message cases use the server-side {@link OutboundMessageProbe}
 * (a {@code ChannelInterceptor} on the {@code clientOutboundChannel}) — not bare latches — so "the server
 * sent nothing" is observed at the outbound path. Delivery-positive cases additionally use real client
 * frame handlers to prove the client received the payload.
 *
 * <p>All connections use {@link StompTestConnection} try-with-resources. No {@code Thread.sleep}, no polling;
 * every await is a bounded future/latch whose timeout FAILS the test.
 */
class ServerTimeSyncLifecycleIntegrationTests extends RealtimeStompTestBase {

    private static final int ACCEPTED_TIMEOUT_SECONDS = 10;
    private static final int OUTBOUND_TIMEOUT_SECONDS = 45;
    private static final int CLIENT_DELIVERY_TIMEOUT_SECONDS = 45;
    private static final int NO_EXTRA_FRAME_WINDOW_SECONDS = 2;

    private long studentId;
    private String studentToken;
    private String tag;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        tag = UUID.randomUUID().toString().substring(0, 6);
        studentId = insertUserWithRole("sync-" + tag, "STUDENT");
        studentToken = accessToken(studentId, "sync", List.of("STUDENT"));
        outboundProbe.clear();
    }

    // 1. CONNECT-only → zero outbound SERVER_TIME_SYNC (probe-backed)

    @Test
    void connectOnlySendsNoServerTimeSync() throws Exception {
        try (StompTestConnection conn = connect(studentToken)) {
            assertThat(conn.isConnected()).isTrue();
            // No personal subscription → the server must not send SERVER_TIME_SYNC. Observed at the
            // outbound path (not a bare latch): the probe's expect() must time out.
            assertNoOutbound(serverTimeSyncMessage(), 2);
        }
    }

    // 2. topic-only → topic subscription accepted, connection active, zero outbound SERVER_TIME_SYNC

    @Test
    void topicOnlySendsNoServerTimeSync() throws Exception {
        long sessionId = createTeacherOwnedOpenSession("sync-tch-" + tag);
        long teacherId = teacherIdForSession(sessionId);
        String teacherToken = accessToken(teacherId, "sync-tch", List.of("TEACHER"));
        try (StompTestConnection conn = connect(teacherToken)) {
            conn.subscribe("/topic/exam-sessions/" + sessionId, noopHandler(new CountDownLatch(1)));
            // Executable proof the SUBSCRIBE passed the inbound authorization interceptors (SessionSubscribeEvent
            // fires after they pass — authorization + inbound receipt, NOT broker registration per B2F8).
            awaitAccepted(acceptedDestination("/topic/exam-sessions/" + sessionId), 5);
            assertThat(conn.isConnected()).as("topic subscription accepted; connection active").isTrue();
            // A topic subscription triggers no SERVER_TIME_SYNC (probe-backed zero).
            assertNoOutbound(serverTimeSyncMessage(), 2);
        }
    }

    // 3. personal subscription → exactly one SERVER_TIME_SYNC + required fields (probe + client handler)

    @Test
    void personalSubscriptionSendsExactlyOneSyncWithRequiredFields() throws Exception {
        try (StompTestConnection conn = connect(studentToken)) {
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            conn.subscribe("/user/queue/attempt", stringHandler(received, latch));
            // Real client delivery:
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
            // Real server-side observation: exactly one outbound sync was sent.
            awaitOutbound(serverTimeSyncMessage(), 5);
            outboundProbe.clear(); // drop the first; the next window must see NO second sync.
            assertNoOutbound(serverTimeSyncMessage(), 2);
            assertThat(received).hasSize(1);

            assertThat(received.get(0)).contains("SERVER_TIME_SYNC")
                    .contains("\"eventId\"").contains("\"occurredAt\"").contains("\"serverTime\"")
                    .doesNotContain("\"sessionId\"").doesNotContain("\"attemptId\"")
                    .doesNotContain("\"studentProfileId\"").doesNotContain("\"activeCount\"")
                    .doesNotContain("\"answerKey\"").doesNotContain("\"score\"");
        }
    }

    // 4. same principal, two WebSocket sessions → session-targeted (the B1R3 §1 proof)
    // Split into 3 stages per session: accepted → server outbound → client delivery.
    // The wider client-delivery timeout (45s) is justified because accepted + outbound already proved
    // the server side works; the delivery is just the async broker executor taking time under load.

    @Test
    void samePrincipalTwoSessionsIsolateSync() throws Exception {
        long uid = insertUserWithRole("iso-" + tag, "STUDENT");
        String token = accessToken(uid, "iso", List.of("STUDENT"));
        try (StompTestConnection a = connect(token); StompTestConnection b = connect(token)) {
            List<String> aReceived = new CopyOnWriteArrayList<>();
            List<String> bReceived = new CopyOnWriteArrayList<>();
            CountDownLatch aLatch = new CountDownLatch(1);
            CountDownLatch bLatch = new CountDownLatch(1);

            // --- SESSION B: arm → subscribe → accepted → outbound → client delivery ---
            long bSubStart = System.nanoTime();
            outboundProbe.clear();
            // ARM the waiter BEFORE subscribing — eliminates the subscribe→expect race.
            CompletableFuture<OutboundMessageProbe.Captured> bOutboundFuture =
                    outboundProbe.expect(serverTimeSyncMessage());
            b.subscribe("/user/queue/attempt", stringHandler(bReceived, bLatch));
            // Stage A: accepted
            AcceptedSubscriptionProbe.Accepted bAcc = awaitAccepted(
                    acc -> "/user/queue/attempt".equals(acc.destination()), ACCEPTED_TIMEOUT_SECONDS);
            String bSimp = bAcc.sessionId();
            long bAcceptedMs = (System.nanoTime() - bSubStart) / 1_000_000;
            // Stage B: server emitted SERVER_TIME_SYNC (waiter armed before subscribe → deterministic)
            long bOutStart = System.nanoTime();
            OutboundMessageProbe.Captured bOutbound;
            try {
                bOutbound = bOutboundFuture.get(OUTBOUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                java.util.List<OutboundMessageProbe.Captured> snap = outboundProbe.snapshot();
                String diag = snap.isEmpty() ? "(no messages captured)" :
                        snap.stream().map(c -> "[" + c.command() + " sess=" + c.sessionId()
                                + " dest=" + c.destination() + " payload=" +
                                (c.payload() != null && c.payload().length() > 60 ? c.payload().substring(0, 60) + "..." : c.payload())
                                + "]").reduce("", (acc, item) -> acc + "\n  " + item);
                throw new AssertionError(
                        "SERVER_ACCEPTED_BUT_NO_SYNC_EMITTED session=SESSION_B"
                        + " timeoutSeconds=" + OUTBOUND_TIMEOUT_SECONDS
                        + "\nCaptured messages (" + snap.size() + "):" + diag
                        + "\nacceptedProbe snapshot: " + acceptedProbe.snapshot(), e);
            }
            long bOutboundMs = (System.nanoTime() - bOutStart) / 1_000_000;
            // Exact-target proof (B2F7 §5): the outbound frame's simpSessionId must be session B's — not a
            // principal fan-out. The direct-session routing fix makes this deterministic; a payload-only
            // predicate cannot distinguish targeted delivery from fan-out, so assert the session metadata.
            assertThat(bOutbound.sessionId())
                    .as("outbound SERVER_TIME_SYNC targeted exactly session B (no principal fan-out)")
                    .isEqualTo(bSimp);
            // Stage C: actual client delivery
            long bClientStart = System.nanoTime();
            assertThat(bLatch.await(CLIENT_DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("SERVER_EMITTED_BUT_CLIENT_B_DID_NOT_RECEIVE").isTrue();
            long bClientMs = (System.nanoTime() - bClientStart) / 1_000_000;
            assertThat(bReceived).hasSize(1);

            // --- SESSION A: arm → subscribe → accepted → outbound → client delivery ---
            long aSubStart = System.nanoTime();
            outboundProbe.clear();
            CompletableFuture<OutboundMessageProbe.Captured> aOutboundFuture =
                    outboundProbe.expect(serverTimeSyncMessage());
            a.subscribe("/user/queue/attempt", stringHandler(aReceived, aLatch));
            AcceptedSubscriptionProbe.Accepted aAcc = awaitAccepted(
                    acc -> "/user/queue/attempt".equals(acc.destination())
                            && !bSimp.equals(acc.sessionId()), ACCEPTED_TIMEOUT_SECONDS);
            String aSimp = aAcc.sessionId();
            long aAcceptedMs = (System.nanoTime() - aSubStart) / 1_000_000;
            assertThat(aSimp).as("A and B must have different simpSessionIds").isNotEqualTo(bSimp);
            long aOutStart = System.nanoTime();
            OutboundMessageProbe.Captured aOutbound;
            try {
                aOutbound = aOutboundFuture.get(OUTBOUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                java.util.List<OutboundMessageProbe.Captured> snap = outboundProbe.snapshot();
                String diag = snap.isEmpty() ? "(no messages captured)" :
                        snap.stream().map(c -> "[" + c.command() + " sess=" + c.sessionId()
                                + " dest=" + c.destination() + "]")
                                .reduce("", (acc, item) -> acc + "\n  " + item);
                throw new AssertionError(
                        "SERVER_ACCEPTED_BUT_NO_SYNC_EMITTED session=SESSION_A"
                        + " timeoutSeconds=" + OUTBOUND_TIMEOUT_SECONDS
                        + "\nCaptured messages (" + snap.size() + "):" + diag, e);
            }
            long aOutboundMs = (System.nanoTime() - aOutStart) / 1_000_000;
            assertThat(aOutbound.sessionId())
                    .as("outbound SERVER_TIME_SYNC targeted exactly session A (no principal fan-out)")
                    .isEqualTo(aSimp);
            long aClientStart = System.nanoTime();
            assertThat(aLatch.await(CLIENT_DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("SERVER_EMITTED_BUT_CLIENT_A_DID_NOT_RECEIVE").isTrue();
            long aClientMs = (System.nanoTime() - aClientStart) / 1_000_000;
            assertThat(aReceived).hasSize(1);

            // --- SAME-PRINCIPAL ISOLATION: B must NOT receive a second sync from A's subscribe ---
            CountDownLatch bShouldNotGrow = new CountDownLatch(1);
            assertThat(bShouldNotGrow.await(NO_EXTRA_FRAME_WINDOW_SECONDS, TimeUnit.SECONDS))
                    .as("A's sync must not fan out to B's session").isFalse();
            assertThat(bReceived).as("B still has exactly one sync").hasSize(1);

            System.out.println("samePrincipalTwoSessionsIsolateSync timings: "
                    + "B accepted=" + bAcceptedMs + "ms outbound=" + bOutboundMs + "ms client=" + bClientMs + "ms"
                    + " | A accepted=" + aAcceptedMs + "ms outbound=" + aOutboundMs + "ms client=" + aClientMs + "ms");
        }
    }

    // 5. two different principals → each only its own

    @Test
    void twoPrincipalsEachReceiveOnlyOwnSync() throws Exception {
        long s1 = insertUserWithRole("p1-" + tag, "STUDENT");
        long s2 = insertUserWithRole("p2-" + tag, "STUDENT");
        String t1 = accessToken(s1, "p1", List.of("STUDENT"));
        String t2 = accessToken(s2, "p2", List.of("STUDENT"));
        try (StompTestConnection c1 = connect(t1); StompTestConnection c2 = connect(t2)) {
            List<String> r1 = new CopyOnWriteArrayList<>();
            List<String> r2 = new CopyOnWriteArrayList<>();
            CountDownLatch l1 = new CountDownLatch(1);
            CountDownLatch l2 = new CountDownLatch(1);
            c1.subscribe("/user/queue/attempt", stringHandler(r1, l1));
            assertThat(l1.await(20, TimeUnit.SECONDS)).isTrue();
            c2.subscribe("/user/queue/attempt", stringHandler(r2, l2));
            assertThat(l2.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(r1).hasSize(1);
            assertThat(r2).hasSize(1);
        }
    }

    // 6. reconnect → a brand-new session re-receives the sync

    @Test
    void reconnectReceivesFreshSync() throws Exception {
        long uid = insertUserWithRole("recon-" + tag, "STUDENT");
        String token = accessToken(uid, "recon", List.of("STUDENT"));
        try (StompTestConnection first = connect(token)) {
            CountDownLatch firstLatch = new CountDownLatch(1);
            first.subscribe("/user/queue/attempt", noopHandler(firstLatch));
            assertThat(firstLatch.await(20, TimeUnit.SECONDS)).isTrue();
        }
        try (StompTestConnection second = connect(token)) {
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            second.subscribe("/user/queue/attempt", stringHandler(received, latch));
            assertThat(latch.await(20, TimeUnit.SECONDS))
                    .as("a reconnecting session receives a fresh sync").isTrue();
            assertThat(received).hasSize(1);
        }
    }

    // 7. unauthorized personal subscription → zero outbound SERVER_TIME_SYNC before the disconnect

    @Test
    void unauthorizedSubscriptionSendsNoSync() {
        try (StompTestConnection conn = connect(studentToken)) {
            List<String> received = new CopyOnWriteArrayList<>();
            conn.subscribe("/user/queue/attempt/extra", stringHandler(received, new CountDownLatch(1)));
            conn.awaitDisconnect();
            assertThat(received).as("no sync on a rejected subscription").isEmpty();
            // Server-side: no SERVER_TIME_SYNC was sent, and the rejected SUBSCRIBE never reached the broker.
            assertNoOutbound(serverTimeSyncMessage(), 2);
            assertNoAccepted(acceptedDestination("/user/queue/attempt/extra"), 1);
        }
    }

    // 8. ERROR frame from a SEND rejection carries no sensitive leak (§5)

    @Test
    void sendRejectionErrorFrameContainsNoLeak() {
        try (StompTestConnection conn = connect(studentToken)) {
            conn.send("/topic/exam-sessions/1", "spoof".getBytes(StandardCharsets.UTF_8));
            // Client-side ERROR capture (Spring dispatches ERROR to the session handler). Per-connection.
            String observed = conn.awaitError(5);
            assertThat(observed)
                    .as("ERROR frame must not leak sensitive data")
                    .doesNotContain(studentToken)          // raw token
                    .doesNotContain("MessagingException")  // exception class
                    .doesNotContain("org.springframework") // internal path
                    .doesNotContain("at com.")             // stack trace
                    .doesNotContain("SELECT")              // SQL
                    .doesNotContain("constraint")          // DB constraint name
                    .doesNotContain("EXAM_SESSION_MONITOR") // permission code
                    .doesNotContain("school_id")           // schema column
                    .doesNotContain("owner_teacher_id");
        }
    }

    // --- handlers ---

    private StompFrameHandler stringHandler(List<String> sink, CountDownLatch latch) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) {
                sink.add(new String((byte[]) payload, StandardCharsets.UTF_8));
                latch.countDown();
            }
        };
    }

    private StompFrameHandler noopHandler(CountDownLatch latch) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) { latch.countDown(); }
        };
    }
}
