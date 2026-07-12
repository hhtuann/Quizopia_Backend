package com.quizopia.backend.realtime.sync;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * B1R4-B2F8 — emits {@code SERVER_TIME_SYNC} AFTER the simple broker has finished registering the delivery
 * subscription, closing the registration race.
 *
 * <p><b>Why this exists.</b> Spring fires {@code SessionSubscribeEvent} once {@code clientInboundChannel.send()}
 * returns ({@code StompSubProtocolHandler} ~line 335), and the {@code brokerChannel} is async. So emitting on the
 * event (the B2F6/B2F7 design) races with the broker's subscription registration, which happens on the
 * {@code brokerChannel}: {@code UserDestinationMessageHandler} transforms the client's {@code /user/queue/attempt}
 * SUBSCRIBE into {@code /queue/attempt-user{sessionId}} and forwards it to the broker, where
 * {@code SimpleBrokerMessageHandler.registerSubscription} runs. If the {@code SERVER_TIME_SYNC} MESSAGE (also sent
 * to the {@code brokerChannel}) arrives before that registration completes, the broker finds no matching
 * subscription and silently drops it → 0 outbound (the observed multi-class miss).
 *
 * <p><b>This interceptor</b> is registered on the {@code brokerChannel} and emits ONLY in
 * {@link #afterMessageHandled} — i.e. AFTER {@code SimpleBrokerMessageHandler} has handled the transformed
 * SUBSCRIBE (registration guaranteed). Conditions: no exception, handler is the simple broker, STOMP SUBSCRIBE,
 * original destination {@code /user/queue/attempt}, valid principal + simpSessionId + subscriptionId. Then it
 * delegates to {@link ServerTimeSyncListener#emitIfNeeded} which deduplicates and sends via direct-session
 * routing (B2F7). No double emission: the listener no longer listens on {@code SessionSubscribeEvent}.
 */
@Component
public class ServerTimeSyncBrokerInterceptor implements ExecutorChannelInterceptor {

    private static final String PERSONAL_QUEUE = "/user/queue/attempt";

    private final ServerTimeSyncListener listener;

    public ServerTimeSyncBrokerInterceptor(ServerTimeSyncListener listener) {
        this.listener = listener;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        if (ex != null) {
            return; // broker threw — subscription not registered; do not emit
        }
        if (!(handler instanceof SimpleBrokerMessageHandler)) {
            return; // only the simple broker's completion proves registration
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return;
        }
        // On the broker channel the destination is the transformed /queue/attempt-user{sessionId}; the
        // ORIGINAL_DESTINATION native header carries the client's /user/queue/attempt.
        String originalDestination = accessor.getFirstNativeHeader(SimpMessageHeaderAccessor.ORIGINAL_DESTINATION);
        if (!PERSONAL_QUEUE.equals(originalDestination)) {
            return;
        }
        Principal user = SimpMessageHeaderAccessor.getUser(accessor.getMessageHeaders());
        if (user == null || user.getName() == null) {
            return; // not authenticated — CONNECT interceptor already rejected; defensive
        }
        String simpSessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (simpSessionId == null) {
            return;
        }
        listener.emitIfNeeded(simpSessionId, subscriptionId);
    }
}
