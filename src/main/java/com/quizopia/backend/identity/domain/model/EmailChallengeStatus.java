package com.quizopia.backend.identity.domain.model;

/**
 * Lifecycle of an email-verification OTP challenge (ADR-002 §2).
 */
public enum EmailChallengeStatus {
    /** Issued, not yet verified. At most one per (user, purpose). */
    PENDING,
    /** The OTP matched; the challenge is spent (one-time use). */
    CONSUMED,
    /** A newer challenge replaced this one (resend). */
    SUPERSEDED,
    /** Too many wrong OTP attempts; a resend is required. */
    FAILED
}
