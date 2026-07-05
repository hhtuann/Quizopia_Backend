package com.hhtuann.backend.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executable evidence for the {@link StompTestConnection} lifecycle (B1R4-A1 §3, B1R4-A2 §1). Real STOMP
 * connections against the running server — not reflection. Proves:
 * <ul>
 *   <li>sequential open/close of multiple clients;</li>
 *   <li>two live clients carry independent transport-error futures (A's rejected frame completes A only;
 *       B stays connected; rejecting B then completes B only);</li>
 *   <li>cleanup stops the client even when the server already closed the session;</li>
 *   <li>a rejected CONNECT leaves no running harness client;</li>
 *   <li>{@code awaitDisconnect} on a non-rejected connection <b>times out and fails</b> (regression for §1);</li>
 *   <li>an intentional over-stop is detected (registry self-balances, no leak into the next test);</li>
 *   <li>a test-body failure still runs try-with-resources cleanup (registry returns to zero).</li>
 * </ul>
 *
 * <p>The suite-wide client-leak gate lives in {@link RealtimeStompTestBase} (before+after each test),
 * so this class no longer resets the counter — a leak from any prior test surfaces there.
 */
class StompTestConnectionLifecycleIntegrationTests extends RealtimeStompTestBase {

    private String studentToken;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        long uid = insertUserWithRole("life-" + tag, "STUDENT");
        studentToken = accessToken(uid, "life", List.of("STUDENT"));
    }

    @Test
    void sequentialOpenCloseMultipleClients() {
        for (int i = 0; i < 3; i++) {
            try (StompTestConnection conn = connect(studentToken)) {
                assertThat(conn.isConnected()).isTrue();
            }
        }
        // three created + three stopped → registry back to zero (also asserted by the base @AfterEach)
        assertThat(ClientRegistry.outstanding()).isZero();
    }

    @Test
    void rejectedFrameOnACompletesOnlyA_NotB() {
        try (StompTestConnection a = connect(studentToken); StompTestConnection b = connect(studentToken)) {
            assertThat(a.isConnected()).isTrue();
            assertThat(b.isConnected()).isTrue();

            a.send("/topic/exam-sessions/1", "spoof".getBytes(StandardCharsets.UTF_8));
            a.awaitDisconnect();

            // B is unaffected: its transport-error future is NOT completed, and it stays connected.
            assertThat(b.transportError.isDone())
                    .as("B's transport-error future must not be completed by A's rejection").isFalse();
            assertThat(b.isConnected()).as("B must still be connected after A was rejected").isTrue();

            b.send("/topic/exam-sessions/1", "spoof".getBytes(StandardCharsets.UTF_8));
            b.awaitDisconnect();
        }
    }

    @Test
    void cleanupStopsClientWhenSessionAlreadyServerClosed() {
        StompTestConnection conn = connect(studentToken);
        conn.send("/queue/attempt", "spoof".getBytes(StandardCharsets.UTF_8));
        conn.awaitDisconnect();
        assertThat(conn.isConnected()).isFalse();

        // close() must still stop the client and balance the registry despite the session being closed.
        conn.close();
        assertThat(ClientRegistry.outstanding())
                .as("client stopped and accounted for after server-side close").isZero();
    }

    @Test
    void connectRejectionLeavesNoRunningClient() {
        assertThatThrownBy(() -> connect("not-a-valid-jwt")).isInstanceOf(Exception.class);
        assertThat(ClientRegistry.outstanding())
                .as("no client may remain running after a rejected CONNECT").isZero();
    }

    @Test
    void awaitDisconnectTimesOutAndFailsWhenNoFrameIsRejected() {
        try (StompTestConnection conn = connect(studentToken)) {
            assertThatThrownBy(() -> conn.awaitDisconnect(1))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("timed out");
            assertThat(conn.isConnected()).isTrue();
        }
    }

    @Test
    void intentionalOverStopIsDetectedAndRebalances() {
        // A balanced created/stopped pair, then an extra stopped() — must throw AND leave the counter at 0
        // (self-rebalanced) so it cannot mask a real leak in the next test.
        ClientRegistry.created();
        ClientRegistry.stopped();
        assertThatThrownBy(ClientRegistry::stopped)
                .as("an over-stop (stopped without a matching created) must be detected")
                .isInstanceOf(IllegalStateException.class);
        assertThat(ClientRegistry.outstanding())
                .as("over-stop must self-rebalance to zero, not leak a negative counter").isZero();
    }

    @Test
    void testBodyFailureStillRunsTryWithResourcesCleanup() {
        // A failing test body must NOT skip try-with-resources close() — the connection is still closed
        // and the registry returns to zero.
        try (StompTestConnection conn = connect(studentToken)) {
            assertThat(conn.isConnected()).isTrue();
            throw new AssertionError("intentional body failure — close() must still run");
        } catch (AssertionError expected) {
            // close() ran via try-with-resources before this catch
        }
        assertThat(ClientRegistry.outstanding())
                .as("cleanup ran despite the test-body failure").isZero();
    }
}
