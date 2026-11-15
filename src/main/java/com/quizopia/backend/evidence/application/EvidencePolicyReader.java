package com.quizopia.backend.evidence.application;

import com.quizopia.backend.exam.application.SessionMonitoringPolicyService;
import com.quizopia.backend.exam.domain.model.SessionMonitoringPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Read-only projection of the frozen session monitoring policy for the
 * evidence/recording services (P4-D). Missing policy row = legacy all-OFF
 * session — no recording, no snapshots, no clips, default retention.
 */
@Component
public class EvidencePolicyReader {

    private final SessionMonitoringPolicyService policies;
    private final Clock clock;

    public EvidencePolicyReader(SessionMonitoringPolicyService policies, Clock clock) {
        this.policies = policies;
        this.clock = clock;
    }

    /** The P4-D-relevant slice of a session's policy. */
    public record MediaPolicy(
            boolean monitoringEnabled,
            boolean snapshotsEnabled,
            boolean fullRecordingEnabled,
            boolean clipsEnabled,
            int mediaRetentionDays,
            int activityRetentionDays) {

        /** Rolling-buffer mode: clips demanded while full recording is OFF. */
        public boolean rollingBufferActive() {
            return clipsEnabled && !fullRecordingEnabled;
        }

        public Instant mediaRetentionExpiry(Instant from) {
            return from.plus(mediaRetentionDays, ChronoUnit.DAYS);
        }
    }

    public MediaPolicy policyOf(long examSessionId) {
        Optional<SessionMonitoringPolicy> policy = policies.findPolicy(examSessionId);
        if (policy.isEmpty()) {
            return new MediaPolicy(false, false, false, false, 30, 90);
        }
        SessionMonitoringPolicy p = policy.get();
        return new MediaPolicy(p.isMonitoringEnabled(),
                p.isMonitoringEnabled() && p.isAiDetectionEnabled() && p.isEvidenceSnapshotsEnabled(),
                p.isMonitoringEnabled() && p.isFullRecordingEnabled(),
                p.isMonitoringEnabled() && p.isEvidenceClipsEnabled(),
                p.getMediaRetentionDays(), p.getActivityRetentionDays());
    }
}
