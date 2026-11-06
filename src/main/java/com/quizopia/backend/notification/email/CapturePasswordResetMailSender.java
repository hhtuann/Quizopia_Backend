package com.quizopia.backend.notification.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only {@link PasswordResetMailSender}: records the reset URLs in memory
 * instead of talking to SMTP, mirroring {@link CaptureOtpMailSender}. Enabled
 * by {@code quizopia.mail.mode=capture} (test profile only); production runs
 * {@code quizopia.mail.mode=smtp} (Mailpit in the compose stack, a real
 * provider in production).
 */
@Component
@ConditionalOnProperty(name = "quizopia.mail.mode", havingValue = "capture")
public class CapturePasswordResetMailSender implements PasswordResetMailSender {

    private final Map<String, String> latestUrlByEmail = new ConcurrentHashMap<>();
    private final Map<String, Integer> sendsByEmail = new ConcurrentHashMap<>();

    @Override
    public boolean sendPasswordResetMail(String toEmail, String displayName, String resetUrl,
            long expiresInSeconds) {
        latestUrlByEmail.put(toEmail.toLowerCase(), resetUrl);
        sendsByEmail.merge(toEmail.toLowerCase(), 1, Integer::sum);
        return true;
    }

    /** Latest reset URL mailed to the address, or {@code null} if none was sent. */
    public String latestUrlFor(String email) {
        return latestUrlByEmail.get(email.toLowerCase());
    }

    /** Number of reset mails recorded for the address. */
    public int sendCountFor(String email) {
        return sendsByEmail.getOrDefault(email.toLowerCase(), 0);
    }

    public void reset() {
        latestUrlByEmail.clear();
        sendsByEmail.clear();
    }
}
