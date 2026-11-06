package com.quizopia.backend.notification.email;

/**
 * Sends the purpose-specific password-reset mail (ADR-004 §"Reset
 * persistence, intake and delivery"). Distinct from the OTP sender on
 * purpose: the body carries a single-use reset URL whose fragment holds the
 * opaque token. Implementations must never log the recipient, the URL or the
 * token. Delivery is best-effort: callers treat a failure as "not delivered"
 * and the user retries the forgot flow after the cooldown.
 */
public interface PasswordResetMailSender {

    /**
     * Sends the reset mail to the issuance-captured address.
     *
     * @param resetUrl        trusted configured reset URL ending {@code #token=<opaque>}
     * @param expiresInSeconds challenge TTL in seconds, for the body copy
     * @return {@code true} when the transport accepted the mail
     */
    boolean sendPasswordResetMail(String toEmail, String displayName, String resetUrl, long expiresInSeconds);
}
