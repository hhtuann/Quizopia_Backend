package com.quizopia.backend.notification.email;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background executor for OTP mail dispatch (review R3-M1-03).
 *
 * <p>The RESEND endpoint is enumeration-sensitive: if the eligible path
 * performed synchronous SMTP while unknown/verified paths returned
 * immediately, response LATENCY would leak email state despite identical
 * bodies. Resend delivery therefore runs on this small background executor —
 * the HTTP path for every state performs only short, similar work (an HMAC
 * comparison and at most one small transaction), and the dominating SMTP
 * latency never sits on the request path. Registration keeps synchronous
 * delivery: its response reports {@code emailSent}, and it is a
 * self-inflicted call (the caller just proved the state by registering).
 *
 * <p>Delivery remains best-effort with the resend endpoint as the recovery
 * flow; a lost task is equivalent to an SMTP outage (challenge exists,
 * undelivered, user may resend after the cooldown).
 */
@Configuration
public class OtpDeliveryConfig {

    private static final int POOL_SIZE = 2;

    @Bean(destroyMethod = "shutdown")
    public ExecutorService otpDeliveryExecutor() {
        ExecutorService executor = Executors.newFixedThreadPool(POOL_SIZE, runnable -> {
            Thread thread = new Thread(runnable, "otp-mail-delivery");
            thread.setDaemon(true);
            return thread;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));
        return executor;
    }
}
