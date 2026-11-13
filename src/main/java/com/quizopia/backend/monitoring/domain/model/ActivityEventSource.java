package com.quizopia.backend.monitoring.domain.model;

/**
 * Source/provenance of an activity event (P4-A2, issue #81: "Client-observed
 * vs backend-confirmed events are typed and distinguishable").
 *
 * <ul>
 *   <li>{@link #CLIENT} — best-effort browser/client observation. NOT
 *       definitive evidence of anything (ADR-008 invariant 8).</li>
 *   <li>{@link #SERVER} — recorded by the backend as a confirmed fact
 *       (attempt lifecycle transitions, an accepted autosave).</li>
 * </ul>
 */
public enum ActivityEventSource {
    CLIENT,
    SERVER
}
