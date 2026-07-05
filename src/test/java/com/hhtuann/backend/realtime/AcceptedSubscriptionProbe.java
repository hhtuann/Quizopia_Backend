package com.hhtuann.backend.realtime;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Test-only server-side observation that a STOMP {@code SUBSCRIBE} passed the inbound authorization
 * interceptors (B1R4-A2 §3). Spring fires {@link SessionSubscribeEvent} after the inbound
 * {@code clientInboundChannel} interceptors pass the frame and it enters inbound processing — so an event
 * recorded here is executable evidence the SUBSCRIBE was AUTHORIZED and entered inbound processing; a
 * rejected SUBSCRIBE (interceptor threw) produces NO event.
 *
 * <p><b>NOT broker-registration proof (corrected B2F8).</b> This event does NOT prove the broker has
 * handled the SUBSCRIBE, that the subscription is stored in the broker registry, or that the broker is
 * ready to deliver a MESSAGE. B2F8 proved {@code SimpleBrokerMessageHandler} registers the delivery
 * subscription later — asynchronously, on the {@code brokerChannel} ({@code afterMessageHandled} is the
 * first registration-guaranteed point). For delivery-stage evidence, tests additionally await the
 * outbound/client-delivery stages.
 *
 * <p>Use cases:
 * <ul>
 *   <li>authorized topic/personal subscription → {@code awaitAccepted(predicate, timeout)} returns;</li>
 *   <li>rejected SUBSCRIBE → {@code assertNoAccepted(predicate, timeout)} proves no event was published.</li>
 * </ul>
 *
 * <p>Same race-free {@code expect}-then-scan pattern as {@link OutboundMessageProbe}; a predicate throw
 * is recorded into a failure sink (never silently swallowed). No polling, no {@code Thread.sleep}.
 */
public class AcceptedSubscriptionProbe {

    public record Accepted(String sessionId, String destination) {}

    private final List<Accepted> accepted = new CopyOnWriteArrayList<>();
    private final List<Waiter> waiters = new CopyOnWriteArrayList<>();
    private final List<Throwable> probeFailures = new CopyOnWriteArrayList<>();

    @EventListener
    public void onSessionSubscribe(SessionSubscribeEvent event) {
        try {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
            Accepted a = new Accepted(accessor.getSessionId(), accessor.getDestination());
            accepted.add(a);
            for (Waiter w : waiters) {
                if (!w.done) {
                    try {
                        if (w.predicate.test(a)) {
                            w.done = true;
                            w.future.complete(a);
                        }
                    } catch (Throwable predicateFailure) {
                        probeFailures.add(predicateFailure);
                    }
                }
            }
        } catch (Throwable t) {
            probeFailures.add(t);
        }
    }

    public CompletableFuture<Accepted> expect(Predicate<Accepted> predicate) {
        CompletableFuture<Accepted> future = new CompletableFuture<>();
        Waiter waiter = new Waiter(predicate, future);
        waiters.add(waiter); // register BEFORE scanning already-captured
        for (Accepted a : accepted) {
            if (!waiter.done && predicate.test(a)) {
                waiter.done = true;
                future.complete(a);
                break;
            }
        }
        future.whenComplete((r, e) -> waiters.remove(waiter));
        return future;
    }

    public List<Accepted> snapshot() {
        return List.copyOf(accepted);
    }

    public List<Throwable> failures() {
        return List.copyOf(probeFailures);
    }

    public void clear() {
        accepted.clear();
        waiters.clear();
        probeFailures.clear();
    }

    private static final class Waiter {
        final Predicate<Accepted> predicate;
        final CompletableFuture<Accepted> future;
        volatile boolean done;
        Waiter(Predicate<Accepted> predicate, CompletableFuture<Accepted> future) {
            this.predicate = predicate;
            this.future = future;
        }
    }
}
