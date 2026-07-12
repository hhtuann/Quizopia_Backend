package com.quizopia.backend.realtime.sync;

import com.quizopia.backend.realtime.event.RealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the {@code SERVER_TIME_SYNC} dedup state + cleanup lifecycle (B1R4-A §3, B1R4-B2F8).
 *
 * <p><b>B2F8 — registration-race closure:</b> the sync is NO LONGER emitted on {@code SessionSubscribeEvent}.
 * Spring fires {@code SessionSubscribeEvent} after {@code clientInboundChannel.send()} returns (StompSubProtocolHandler
 * line ~335), and the broker channel is async — so the listener would emit BEFORE the broker finishes registering
 * the delivery subscription ({@code /queue/attempt-user{sessionId}} on the {@code brokerChannel}), and the resolved
 * MESSAGE would find no subscription → dropped (the B2F6/B2F7 "0 messages" miss). Emission now happens in
 * {@link ServerTimeSyncBrokerInterceptor#afterMessageHandled} AFTER {@code SimpleBrokerMessageHandler} completes
 * the (transformed) SUBSCRIBE on the {@code brokerChannel} — i.e. after registration is guaranteed. This class
 * keeps the dedup set and the UNSUBSCRIBE/DISCONNECT cleanup that bounds it.
 *
 * <p><b>Direct session routing (B2F7):</b> {@code sendServerTimeSync(simpSessionId)} passes the session id as
 * BOTH the user argument AND the header, so the resolver's {@code userName.equals(sessionId)} branch targets
 * exactly one session without consulting {@code SimpUserRegistry} — no fan-out to other sessions of the same
 * principal.
 *
 * <p>Thread-safe via a {@link ConcurrentHashMap}-backed set; cleanup never touches another session's keys.
 */
@Component
public class ServerTimeSyncListener {

    private static final Logger log = LoggerFactory.getLogger(ServerTimeSyncListener.class);

    /** Dedup key: the (simpSessionId, subscriptionId) pair that already received a sync. */
    record SubscriptionKey(String sessionId, String subscriptionId) {}

    private final RealtimePublisher publisher;
    /** Dedup set: (simpSessionId, subscriptionId) pairs that already received a sync. */
    private final Set<SubscriptionKey> delivered = ConcurrentHashMap.newKeySet();

    public ServerTimeSyncListener(RealtimePublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Emits one {@code SERVER_TIME_SYNC} for the given (session, subscription), at-most-once. Called by
     * {@link ServerTimeSyncBrokerInterceptor} ONLY after the broker has registered the delivery subscription,
     * so the resolved MESSAGE always finds a matching subscription. Principal/destination/command validation
     * is the interceptor's responsibility; this method only deduplicates and sends.
     */
    public void emitIfNeeded(String simpSessionId, String subscriptionId) {
        if (simpSessionId == null) {
            return;
        }
        // Deduplicate: at-most-once per (session, subscription).
        SubscriptionKey key = new SubscriptionKey(simpSessionId, subscriptionId != null ? subscriptionId : "?");
        if (!delivered.add(key)) {
            log.debug("SERVER_TIME_SYNC already delivered for {}", key);
            return;
        }
        publisher.sendServerTimeSync(simpSessionId);
    }

    /**
     * Removes the single (session, subscription) dedup entry on UNSUBSCRIBE, so a later re-subscribe
     * with a fresh subscription id in the same session receives a new sync.
     */
    @EventListener
    public void onSessionUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String simpSessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (simpSessionId == null) {
            return;
        }
        delivered.remove(new SubscriptionKey(simpSessionId, subscriptionId != null ? subscriptionId : "?"));
    }

    /**
     * Removes EVERY dedup entry belonging to the disconnecting session. Mandatory to keep the set
     * bounded — without it, every historical (session, subscription) pair would accumulate forever.
     * Only keys whose sessionId equals the disconnecting session are removed; other sessions are
     * untouched. Safe to call repeatedly (missing keys are a no-op).
     */
    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String simpSessionId = accessor.getSessionId();
        if (simpSessionId == null) {
            return;
        }
        delivered.removeIf(key -> simpSessionId.equals(key.sessionId()));
    }

    // --- test-only inspection (package-private; no production caller, no REST exposure) ---

    /** Test-only: number of tracked (session, subscription) dedup keys. */
    int deliveredKeyCount() {
        return delivered.size();
    }

    /** Test-only: clears all dedup keys (test setup between scenarios). */
    void clearDelivered() {
        delivered.clear();
    }
}
