package com.quizopia.backend.exam.application;

import com.quizopia.backend.exam.domain.model.ExamSession;
import com.quizopia.backend.exam.domain.model.ExamSessionStatus;
import com.quizopia.backend.exam.domain.model.SessionMonitoringPolicy;
import com.quizopia.backend.exam.domain.model.SessionVisibility;
import com.quizopia.backend.exam.dto.MonitoringPolicyRequest;
import com.quizopia.backend.exam.dto.MonitoringPolicyResponse;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.exam.repository.SessionMonitoringPolicyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * P4-A1 (issue #80): validation, write-gating and projection of the per-session
 * Exam Monitoring policy ({@code session_monitoring_policies}, V33).
 *
 * <p><b>Write gate — configuration freeze</b> (ADR-008 invariant 3): every
 * policy mutation runs under the caller's session row lock
 * ({@code SELECT ... FOR UPDATE} in {@code ExamSessionService}) and is rejected
 * with {@code EXAM_SESSION_MONITORING_FROZEN} (409) when ANY of:
 * <ol>
 *   <li>the session status is not DRAFT/SCHEDULED (i.e. it has OPENed or
 *       later);</li>
 *   <li>the session is SCHEDULED and its scheduled {@code startsAt} has been
 *       reached (server clock) — frozen even if lazy-open has not run;</li>
 *   <li>any Attempt has ever been created for the session.</li>
 * </ol>
 * Conditions 1 and 3 overlap in practice (attempts require an OPEN session)
 * but are checked independently as defense-in-depth, exactly as the issue
 * requires. The optimistic {@code expectedVersion} check on the session
 * update guards against concurrent config races before the lock is acquired.
 *
 * <p><b>Validation matrix</b> (fail 400 {@code EXAM_SESSION_MONITORING_INVALID}):
 * PUBLIC visibility, zero assigned classrooms, feature flags without
 * monitoring, snapshots without AI detection, clips without full recording.
 *
 * <p>All methods join the caller's transaction ({@code SUPPORTS→REQUIRED});
 * no new transaction boundaries are introduced.
 */
@Component
public class SessionMonitoringPolicyService {

    private final SessionMonitoringPolicyRepository policyRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public SessionMonitoringPolicyService(SessionMonitoringPolicyRepository policyRepository,
            NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.policyRepository = policyRepository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ============================================================
    // Read path
    // ============================================================

    /** Policy row of a session; empty = legacy session (all-OFF). */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<SessionMonitoringPolicy> findPolicy(Long examSessionId) {
        return policyRepository.findByExamSessionId(examSessionId);
    }

    /** True only when a policy row exists with monitoring enabled. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isMonitoringEnabled(Long examSessionId) {
        Boolean enabled = policyRepository.isMonitoringEnabled(examSessionId);
        return Boolean.TRUE.equals(enabled);
    }

    /** Detail/eligibility projection, including the derived frozen flag. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public MonitoringPolicyResponse toResponse(ExamSession session, SessionMonitoringPolicy policy) {
        boolean frozen = isConfigurationFrozen(session);
        if (policy == null) {
            MonitoringPolicyResponse off = MonitoringPolicyResponse.off();
            return frozen
                    ? new MonitoringPolicyResponse(false, false, false, false, false, false, false,
                            true, off.mediaRetentionDays(), off.activityRetentionDays())
                    : off;
        }
        return new MonitoringPolicyResponse(
                policy.isMonitoringEnabled(),
                policy.isCameraRequired(),
                policy.isAiDetectionEnabled(),
                policy.isActivityTrackingEnabled(),
                policy.isEvidenceSnapshotsEnabled(),
                policy.isFullRecordingEnabled(),
                policy.isEvidenceClipsEnabled(),
                frozen,
                policy.getMediaRetentionDays(),
                policy.getActivityRetentionDays());
    }

    /** The write-gate conditions, evaluated with the server clock. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isConfigurationFrozen(ExamSession session) {
        if (session.getStatus() != ExamSessionStatus.DRAFT
                && session.getStatus() != ExamSessionStatus.SCHEDULED) {
            return true;
        }
        if (session.getStatus() == ExamSessionStatus.SCHEDULED
                && !Instant.now(clock).isBefore(session.getStartsAt())) {
            return true;
        }
        return attemptsExist(session.getId());
    }

    // ============================================================
    // Write path (caller holds the session row lock)
    // ============================================================

    /**
     * Validates and applies a monitoring configuration. Must be called inside
     * the session create/update/assign transaction, AFTER class assignment, so
     * {@code assignedClassroomCount} reflects the post-write state.
     *
     * @param assignedClassroomCount classrooms assigned to the session after
     *                               this transaction's class writes
     */
    public SessionMonitoringPolicy applyConfiguration(ExamSession session,
            MonitoringPolicyRequest request, int assignedClassroomCount) {
        // Freeze gate — first (before validation) so a frozen session never
        // reveals whether the payload would have been valid.
        if (isConfigurationFrozen(session)) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_FROZEN);
        }

        boolean monitoring = request != null && request.monitoringEnabledOrDefault();
        boolean ai = request != null && request.aiDetectionOrDefault();
        boolean tracking = request != null && request.activityTrackingOrDefault();
        boolean snapshots = request != null && request.evidenceSnapshotsOrDefault();
        boolean recording = request != null && request.fullRecordingOrDefault();
        boolean clips = request != null && request.evidenceClipsOrDefault();

        if (monitoring) {
            // D1: monitoring is reserved to eligible CLASS_RESTRICTED sessions
            // with at least one active assigned class — never PUBLIC.
            if (session.getVisibility() == SessionVisibility.PUBLIC) {
                throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_INVALID,
                        "Monitoring requires a CLASS_RESTRICTED session");
            }
            if (assignedClassroomCount < 1) {
                throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_INVALID,
                        "Monitoring requires at least one assigned active classroom");
            }
        }
        if (ai && !monitoring) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_INVALID,
                    "AI detection requires monitoring to be enabled");
        }
        if (tracking && !monitoring) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_INVALID,
                    "Activity tracking requires monitoring to be enabled");
        }
        if (snapshots && !monitoring) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_INVALID,
                    "Evidence snapshots require monitoring to be enabled");
        }
        if (recording && !monitoring) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_INVALID,
                    "Full recording requires monitoring to be enabled");
        }
        if (clips && !monitoring) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_INVALID,
                    "Evidence clips require monitoring to be enabled");
        }
        if (snapshots && !ai) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_INVALID,
                    "Evidence snapshots require AI detection to be enabled");
        }
        if (clips && !recording) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_INVALID,
                    "Evidence clips require full recording to be enabled in P4-A "
                            + "(rolling-buffer capability arrives in P4-D)");
        }

        SessionMonitoringPolicy policy = policyRepository.findByExamSessionId(session.getId())
                .orElseGet(() -> SessionMonitoringPolicy.offFor(session.getId()));
        policy.configure(monitoring, ai, tracking, snapshots, recording, clips);
        return policyRepository.saveAndFlush(policy);
    }

    /**
     * Guard for class-assignment mutations on monitoring-enabled sessions
     * (issue #80: "Prevent removing the last assigned classroom"): rejects an
     * assignment state that would leave a monitored session with zero classes.
     * The freeze gate itself is enforced by the caller's own status/time
     * checks ({@code assignClasses} already rejects past-startsAt mutations).
     */
    public void requireClassAssignmentCompatibleWithMonitoring(ExamSession session,
            int resultingClassroomCount) {
        if (resultingClassroomCount < 1 && isMonitoringEnabled(session.getId())) {
            throw new ExamException(ExamErrorCode.EXAM_SESSION_MONITORING_INVALID,
                    "Cannot remove the last assigned classroom of a monitoring-enabled session");
        }
    }

    private boolean attemptsExist(Long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_session_id = :sid",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("sid", sessionId),
                Integer.class);
        return count != null && count > 0;
    }
}
