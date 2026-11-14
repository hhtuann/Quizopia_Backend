package com.quizopia.backend.attempt.dto;

/**
 * Learner-facing monitoring summary embedded in
 * {@code SessionEligibilityResponse} (P4-A1, issue #80 acceptance: "Policy
 * notice and feature flags exposed to authorized student only").
 *
 * <p>Deliberately minimal: the landing page needs to render the camera/policy
 * notice and disable Start while monitored sessions fail closed (P4-B camera
 * readiness not yet available). Advanced flags (AI detection, recording,
 * clips) are teacher-facing and are NOT exposed here.
 *
 * @param monitoringEnabled the session's monitoring master switch
 * @param cameraRequired   DERIVED enforced property of monitoringEnabled
 *                         (ADR-008 invariant 2) — always equal to
 *                         monitoringEnabled; repeated so clients can render
 *                         the notice without policy knowledge
 */
public record SessionMonitoringSummary(
        boolean monitoringEnabled,
        boolean cameraRequired) {
}
