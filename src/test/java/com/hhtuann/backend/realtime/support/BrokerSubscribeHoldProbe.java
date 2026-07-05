package com.hhtuann.backend.realtime.support;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * B2F8 §3 — test-only probe that can HOLD the simple broker just before it handles a personal-queue SUBSCRIBE,
 * so the deterministic registration-race test can prove: (a) {@code SessionSubscribeEvent} has ALREADY fired
 * while the broker registration is still incomplete (the race window), and (b) under the B2F8 fix the
 * {@code SERVER_TIME_SYNC} is NOT emitted until the broker completes (after release).
 *
 * <p>Registered on the {@code brokerChannel}. Pass-through unless {@link #arm()}ed. When armed, the next
 * personal-queue SUBSCRIBE reaching {@code SimpleBrokerMessageHandler} signals {@code entered} and blocks on a
 * release latch inside {@code beforeMessageHandler} (before registration runs). No {@code Thread.sleep}; the
 * hold is a bounded {@code CountDownLatch.await()} whose release is test-controlled, and {@code awaitEntered}
 * is a bounded future.
 */
public class BrokerSubscribeHoldProbe implements ExecutorChannelInterceptor {

    private static final String PERSONAL_QUEUE = "/user/queue/attempt";

    private volatile boolean armed = false;
    private volatile CompletableFuture<Void> entered = new CompletableFuture<>();
    private volatile CountDownLatch release = new CountDownLatch(1);

    public synchronized void arm() {
        this.entered = new CompletableFuture<>();
        this.release = new CountDownLatch(1);
        this.armed = true;
    }

    public synchronized void disarm() {
        this.armed = false;
        this.release.countDown();
    }

    public void awaitEntered(long timeoutSeconds) throws Exception {
        entered.get(timeoutSeconds, TimeUnit.SECONDS);
    }

    public void release() {
        this.release.countDown();
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        if (!armed || !(handler instanceof SimpleBrokerMessageHandler)) {
            return message;
        }
        StompHeaderAccessor a = StompHeaderAccessor.wrap(message);
        if (a.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }
        if (!PERSONAL_QUEUE.equals(a.getFirstNativeHeader(SimpMessageHeaderAccessor.ORIGINAL_DESTINATION))) {
            return message;
        }
        entered.complete(null);
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return message;
    }
}
