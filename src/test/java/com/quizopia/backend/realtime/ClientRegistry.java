package com.quizopia.backend.realtime;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only lifecycle counter (B1R4-A1 §3). Tracks the number of {@code WebSocketStompClient} instances
 * the test harness has created but not yet stopped. Every {@code connectWith} path pairs a
 * {@link #created()} with a {@link #stopped()} — on success via {@link StompTestConnection#close()},
 * on CONNECT failure inside {@code connectWith}. An {@code @AfterEach} asserting {@link #outstanding()}
 * == 0 proves no harness client leaked across a test (no dangling scheduler/executor threads).
 *
 * <p>Test-source only — no production endpoint, no production global state, not autowired. Thread-safe
 * via {@link AtomicInteger}. The counter is global to the test JVM, so a leak in one test surfaces in
 * every subsequent {@code @AfterEach} until rebalanced.
 */
final class ClientRegistry {

    private static final AtomicInteger OUTSTANDING = new AtomicInteger(0);

    private ClientRegistry() {}

    /** Called when the harness creates a {@code WebSocketStompClient}. */
    static void created() {
        OUTSTANDING.incrementAndGet();
    }

    /** Called when a created client has been stopped. Throws if stop is called more than create. */
    static void stopped() {
        int v = OUTSTANDING.decrementAndGet();
        if (v < 0) {
            // rebalance so a single over-stop doesn't cascade across the suite
            OUTSTANDING.set(0);
            throw new IllegalStateException("ClientRegistry.stopped() called more than created (counter was " + v + ")");
        }
    }

    /** Number of created-but-not-yet-stopped harness clients. */
    static int outstanding() {
        return OUTSTANDING.get();
    }

    /** Resets the counter (lifecycle test setup, to keep the class self-contained). */
    static void reset() {
        OUTSTANDING.set(0);
    }
}
