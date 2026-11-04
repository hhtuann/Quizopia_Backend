package com.quizopia.backend.notification.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only {@link OtpMailSender}: records OTPs in memory instead of talking
 * to SMTP, so integration tests can complete the verification flow without a
 * mail server. Enabled by {@code quizopia.mail.mode=capture} (set in the test
 * profile only). Production runs {@code quizopia.mail.mode=smtp}.
 */
@Component
@ConditionalOnProperty(name = "quizopia.mail.mode", havingValue = "capture")
public class CaptureOtpMailSender implements OtpMailSender {

    private final Map<String, String> latestOtpByEmail = new ConcurrentHashMap<>();
    private final Map<String, Integer> sendsByEmail = new ConcurrentHashMap<>();

    @Override
    public boolean sendAccountVerificationOtp(String toEmail, String displayName,
            String otp, long expiresInSeconds) {
        latestOtpByEmail.put(toEmail.toLowerCase(), otp);
        sendsByEmail.merge(toEmail.toLowerCase(), 1, Integer::sum);
        return true;
    }

    /** Latest OTP issued for the email, or {@code null} if none was sent. */
    public String latestOtpFor(String email) {
        return latestOtpByEmail.get(email.toLowerCase());
    }

    /** Number of OTP mails recorded for the email. */
    public int sendCountFor(String email) {
        return sendsByEmail.getOrDefault(email.toLowerCase(), 0);
    }

    public void reset() {
        latestOtpByEmail.clear();
        sendsByEmail.clear();
    }
}
