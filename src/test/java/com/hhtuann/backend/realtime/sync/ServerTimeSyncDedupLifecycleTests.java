package com.hhtuann.backend.realtime.sync;

import com.hhtuann.backend.realtime.event.RealtimePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Deterministic, context-free unit tests for the {@link ServerTimeSyncListener} dedup-set lifecycle
 * (B1R4-A §3). Fires synthetic Spring WebSocket events (no real STOMP client, no Docker) at a listener
 * wired with a mocked {@link RealtimePublisher}, and inspects the package-private dedup set directly.
 * Proves: at-most-once per (session, subscription); UNSUBSCRIBE removes one key; DISCONNECT removes
 * only that session's keys (another session untouched); reconnect with a new session id re-receives;
 * repeated cleanup events are no-ops. No production REST endpoint exposes this state.
 */
class ServerTimeSyncDedupLifecycleTests {

    private RealtimePublisher publisher;
    private ServerTimeSyncListener listener;

    @BeforeEach
    void setUp() {
        publisher = mock(RealtimePublisher.class);
        listener = new ServerTimeSyncListener(publisher);
    }

    @Test
    void subscribeAddsEntryAndSendsExactlyOnce() {
        listener.emitIfNeeded("sess-A", "sub-1");

        assertThat(listener.deliveredKeyCount()).isOne();
        verify(publisher, times(1)).sendServerTimeSync("sess-A");
    }

    @Test
    void duplicateKeyDoesNotResend() {
        listener.emitIfNeeded("sess-A", "sub-1");
        listener.emitIfNeeded("sess-A", "sub-1"); // identical (session, subscription)

        assertThat(listener.deliveredKeyCount()).isOne(); // still one entry
        verify(publisher, times(1)).sendServerTimeSync("sess-A"); // sent only the first time
    }

    @Test
    void newSubscriptionIdInSameSessionReceivesNewSync() {
        listener.emitIfNeeded("sess-A", "sub-1");

        listener.emitIfNeeded("sess-A", "sub-2"); // different subscription id

        assertThat(listener.deliveredKeyCount()).isEqualTo(2);
        // sendServerTimeSync takes only the simpSessionId — NOT the subscriptionId — so both calls
        // are identical; there are 2 of them (one per distinct dedup key).
        verify(publisher, times(2)).sendServerTimeSync("sess-A");
    }

    @Test
    void unsubscribeRemovesSingleEntryAllowingResend() {
        listener.emitIfNeeded("sess-A", "sub-1");
        assertThat(listener.deliveredKeyCount()).isOne();

        listener.onSessionUnsubscribe(unsubscribe("sess-A", "sub-1"));
        assertThat(listener.deliveredKeyCount()).isZero();

        // Re-subscribe with the SAME (session, subscription) now re-receives because the key was cleaned.
        listener.emitIfNeeded("sess-A", "sub-1");
        verify(publisher, times(2)).sendServerTimeSync("sess-A");
    }

    @Test
    void disconnectRemovesAllKeysOfThatSession() {
        listener.emitIfNeeded("sess-A", "sub-1");
        listener.emitIfNeeded("sess-A", "sub-2");
        assertThat(listener.deliveredKeyCount()).isEqualTo(2);

        listener.onSessionDisconnect(disconnect("sess-A"));

        assertThat(listener.deliveredKeyCount()).isZero();
    }

    @Test
    void disconnectLeavesOtherSessionEntriesUntouched() {
        listener.emitIfNeeded("sess-A", "sub-1");
        listener.emitIfNeeded("sess-B", "sub-1"); // same principal, other session
        assertThat(listener.deliveredKeyCount()).isEqualTo(2);

        listener.onSessionDisconnect(disconnect("sess-A")); // A disconnects

        assertThat(listener.deliveredKeyCount()).isOne(); // only B's entry remains
        // B can still re-receive on a new subscription id (A's cleanup did not affect B).
        listener.emitIfNeeded("sess-B", "sub-2");
        // sess-B got sub-1 + sub-2 → 2 identical sends (publisher call omits the subscriptionId).
        verify(publisher, times(2)).sendServerTimeSync("sess-B");
    }

    @Test
    void reconnectWithNewSessionIdReceivesSync() {
        listener.emitIfNeeded("sess-A", "sub-1");
        listener.onSessionDisconnect(disconnect("sess-A"));

        // Reconnect — Spring assigns a brand-new simpSessionId.
        listener.emitIfNeeded("sess-A-reborn", "sub-1");

        verify(publisher, times(1)).sendServerTimeSync("sess-A");
        verify(publisher, times(1)).sendServerTimeSync("sess-A-reborn");
    }

    @Test
    void repeatedCleanupEventsAreNoOps() {
        listener.onSessionDisconnect(disconnect("sess-never-subscribed"));
        listener.onSessionUnsubscribe(unsubscribe("sess-never-subscribed", "sub-x"));
        listener.onSessionDisconnect(disconnect("sess-never-subscribed")); // again

        assertThat(listener.deliveredKeyCount()).isZero();
        verify(publisher, never()).sendServerTimeSync(org.mockito.ArgumentMatchers.anyString());
    }

    // --- event builders (UNSUBSCRIBE / DISCONNECT cleanup only — SUBSCRIBE emission is the broker
    //     interceptor's responsibility; destination filtering is verified in ServerTimeSyncPostBrokerRegistrationTests) ---

    private SessionUnsubscribeEvent unsubscribe(String simpSessionId, String subscriptionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        accessor.setSessionId(simpSessionId);
        accessor.setSubscriptionId(subscriptionId);
        return new SessionUnsubscribeEvent(this, message(accessor));
    }

    private SessionDisconnectEvent disconnect(String simpSessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(simpSessionId);
        return new SessionDisconnectEvent(this, message(accessor), simpSessionId, CloseStatus.NORMAL);
    }

    private Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
