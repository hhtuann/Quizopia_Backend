package com.hhtuann.backend.realtime;

import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owns ONE STOMP test connection's full lifecycle (B1R4-A §1, B1R4-A1 §1/§2): the
 * {@link WebSocketStompClient}, its connected {@link StompSession}, a <b>per-connection</b>
 * transport-error future, and the subscriptions created on it. {@link AutoCloseable} so tests use
 * try-with-resources; {@link #close()} unsubscribes every tracked subscription, disconnects the session,
 * stops the client, and verifies it is no longer running — <b>without swallowing cleanup failures</b>.
 *
 * <p><b>Per-connection futures:</b> each {@code StompTestConnection} carries its own transport-error
 * future captured in its {@code StompSessionHandlerAdapter.handleTransportError}, so a rejected frame on
 * session A never satisfies {@code awaitDisconnect(B)}. §3's executable lifecycle test proves this with
 * two live clients.
 *
 * <p><b>awaitDisconnect (§1):</b> blocks on the connection's own transport-error future with a bounded
 * timeout. A {@link TimeoutException} <b>fails the test</b> (it is NOT swallowed as "expected") — the
 * only acceptable completion is the transport-error signal, after which the session must be disconnected.
 * {@link InterruptedException} restores the interrupt flag and fails. No {@code Thread.sleep}, no polling.
 *
 * <p><b>close (§2):</b> every step is attempted regardless of prior failures; failures are aggregated
 * as suppressed exceptions on a single thrown {@link AssertionError} so try-with-resources surfaces them
 * alongside any test-body failure. A session already closed by the server is tolerated (disconnect skipped).
 */
final class StompTestConnection implements AutoCloseable {

    final WebSocketStompClient client;
    final StompSession session;
    final CompletableFuture<Throwable> transportError;
    /** Per-connection ERROR-frame capture (§5): the STOMP {@code message} header + body of the first
     *  ERROR frame the server sends to THIS connection. Completed in the session handler's
     *  {@code handleFrame} (Spring dispatches ERROR frames to the session-level handler, not subscriptions). */
    final CompletableFuture<String> errorFrame;

    private final List<StompSession.Subscription> subscriptions = new CopyOnWriteArrayList<>();

    StompTestConnection(WebSocketStompClient client, StompSession session,
                        CompletableFuture<Throwable> transportError,
                        CompletableFuture<String> errorFrame) {
        this.client = client;
        this.session = session;
        this.transportError = transportError;
        this.errorFrame = errorFrame;
    }

    /** Subscribes AND tracks the subscription so {@link #close()} can unsubscribe it. */
    StompSession.Subscription subscribe(String destination, StompFrameHandler handler) {
        StompSession.Subscription subscription = session.subscribe(destination, handler);
        subscriptions.add(subscription);
        return subscription;
    }

    /** Convenience for byte-payload SENDs (the lenient converter serializes {@code byte[]}). */
    void send(String destination, byte[] payload) {
        session.send(destination, payload);
    }

    boolean isConnected() {
        return session.isConnected();
    }

    void disconnect() {
        session.disconnect();
    }

    /**
     * Blocks until THIS connection's transport-error future completes (the server rejected a frame →
     * ERROR + close → {@code handleTransportError}), bounded to {@code timeoutSeconds}, then asserts the
     * session is no longer connected. A timeout is a HARD FAILURE (the expected transport error never
     * arrived) — never swallowed.
     */
    void awaitDisconnect(long timeoutSeconds) {
        try {
            transportError.get(timeoutSeconds, TimeUnit.SECONDS); // transport-error signal arrived
        } catch (TimeoutException e) {
            throw new AssertionError("awaitDisconnect timed out after " + timeoutSeconds
                    + "s — expected a transport error (a rejected frame closes the session), none arrived", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("awaitDisconnect interrupted while awaiting the transport-error signal", e);
        } catch (ExecutionException e) {
            // transportError itself completed exceptionally — still a signal; fall through to the
            // disconnected assertion below (not swallowed: a still-connected session would then fail).
        }
        assertThat(session.isConnected())
                .as("rejected frame must close the session (transport-error fired but session still connected)")
                .isFalse();
    }

    /** Convenience: default 5s bound. */
    void awaitDisconnect() {
        awaitDisconnect(5);
    }

    /**
     * Awaits the first ERROR frame the server sends to THIS connection (B1R4-A1 §5). Spring dispatches a
     * STOMP ERROR to the session-level {@code handleFrame}; this returns its {@code message} header +
     * body. A timeout FAILS the test (no ERROR observed). Per-connection — A's ERROR never satisfies B.
     */
    String awaitError(long timeoutSeconds) {
        try {
            return errorFrame.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("awaitError timed out after " + timeoutSeconds
                    + "s — no ERROR frame was sent to this connection", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("awaitError interrupted while awaiting the ERROR frame", e);
        } catch (ExecutionException e) {
            throw new AssertionError("ERROR-frame future completed exceptionally", e);
        }
    }

    @Override
    public void close() {
        List<Throwable> failures = new ArrayList<>();
        // 1. unsubscribe tracked subscriptions — tolerate a benign "already closing" failure (the server
        //    closed the session between the isConnected() check and the UNSUBSCRIBE send). A real failure
        //    is aggregated; "already closed" is the goal of unsubscribe, not a cleanup failure.
        if (session.isConnected()) {
            for (StompSession.Subscription subscription : subscriptions) {
                try {
                    subscription.unsubscribe();
                } catch (Throwable t) {
                    if (!isAlreadyClosed(t)) {
                        failures.add(t);
                    }
                }
            }
        }
        subscriptions.clear();
        // 2. disconnect the session — same benign-tolerance for the check-then-use race.
        if (session.isConnected()) {
            try {
                session.disconnect();
            } catch (Throwable t) {
                if (!isAlreadyClosed(t)) {
                    failures.add(t);
                }
            }
        }
        // 3. stop the client (shuts down its scheduler/executor threads — Spring 7 Lifecycle, synchronous).
        try {
            client.stop();
        } catch (Throwable t) {
            failures.add(t);
        }
        // 4. verify the client is no longer running (Lifecycle contract).
        try {
            if (client.isRunning()) {
                failures.add(new IllegalStateException("WebSocketStompClient still running after stop()"));
            }
        } catch (Throwable t) {
            failures.add(t);
        }
        // 5. account for the stopped client in the test registry.
        try {
            ClientRegistry.stopped();
        } catch (Throwable t) {
            failures.add(t);
        }
        if (!failures.isEmpty()) {
            AssertionError aggregated = new AssertionError(
                    "StompTestConnection cleanup had " + failures.size() + " failure(s)");
            failures.forEach(aggregated::addSuppressed);
            throw aggregated;
        }
    }

    /**
     * True if {@code t} indicates the session was already closing/closed by the server between the
     * {@code isConnected()} check and the send (a check-then-use race). Such a failure is benign for
     * unsubscribe/disconnect — the session is closing, which is the goal — and is NOT aggregated.
     * Narrow message match on the cause chain so unrelated failures are still reported.
     */
    private static boolean isAlreadyClosed(Throwable t) {
        Throwable current = t;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("has been closed") || message.contains("Connection closed"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
