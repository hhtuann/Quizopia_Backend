package com.hhtuann.backend.realtime.authorization;

/**
 * Generic denial for a realtime authorization failure (personal-queue / teacher-topic subscription).
 * Carries no reason — by design the response does not distinguish "session missing" from "not owner"
 * or "missing permission", so this type exposes nothing beyond the denial itself.
 */
public class RealtimeAuthorizationException extends RuntimeException {
    public RealtimeAuthorizationException() {
        super("denied");
    }
}
