package com.quizopia.backend.monitoring.domain.model;

/**
 * Lifecycle status of a camera preflight (V37 {@code chk_preflights_status},
 * P4-B2 issue #85).
 *
 * <p>Transitions (server-driven only):
 * <ul>
 *   <li>{@code ACTIVE → VERIFIED} — verify endpoint confirmed an active
 *       camera track and stored a hashed readiness lease.</li>
 *   <li>{@code ACTIVE → EXPIRED} — superseded by a newer preflight of the
 *       same (session, user), or past {@code expiresAt} (housekeeping).</li>
 *   <li>{@code VERIFIED → CONSUMED} — exactly once, atomically, at the start
 *       of a monitored attempt (the one-time lease spend).</li>
 *   <li>{@code VERIFIED → REVOKED} — teacher revoked the camera binding.</li>
 * </ul>
 * There is deliberately no path back: a consumed/expired/revoked preflight
 * requires a fresh preflight with a fresh media identity.
 */
public enum PreflightStatus {
    ACTIVE,
    VERIFIED,
    CONSUMED,
    EXPIRED,
    REVOKED
}
