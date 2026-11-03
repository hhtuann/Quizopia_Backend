package com.quizopia.backend.notification.email;

/**
 * Sends the email-verification OTP mail. Implementations must never log the
 * OTP. Delivery is best-effort: callers treat a failure as "not delivered" and
 * rely on the resend endpoint as the recovery flow (ADR-002 §2).
 */
public interface OtpMailSender {

    /**
     * Sends the OTP to the given address.
     *
     * @return {@code true} when the transport accepted the mail
     */
    boolean sendAccountVerificationOtp(String toEmail, String displayName, String otp, long expiresInSeconds);
}
