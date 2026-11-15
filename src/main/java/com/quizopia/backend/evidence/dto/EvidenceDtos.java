package com.quizopia.backend.evidence.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.quizopia.backend.evidence.domain.MonitoringEvidence;
import com.quizopia.backend.evidence.domain.MonitoringRecordingJob;
import java.time.Instant;
import java.util.UUID;

/** Response DTOs of the P4-D evidence API (issues #91–#94). */
public final class EvidenceDtos {

    private EvidenceDtos() {
    }

    public record EvidenceResponse(
            UUID evidenceId,
            Long attemptId,
            Long alertId,
            String kind,
            String status,
            String provenance,
            @JsonInclude(JsonInclude.Include.NON_NULL) String contentType,
            @JsonInclude(JsonInclude.Include.NON_NULL) String sha256,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long bytes,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer width,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer height,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant capturedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant windowStart,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant windowEnd,
            @JsonInclude(JsonInclude.Include.NON_NULL) String coverage,
            @JsonInclude(JsonInclude.Include.NON_NULL) String error,
            Instant createdAt,
            Instant expiresAt) {

        public static EvidenceResponse from(MonitoringEvidence e) {
            return new EvidenceResponse(e.getId(), e.getAttemptId(), e.getAlertId(),
                    e.getKind(), e.getStatus(), e.getProvenance(), e.getContentType(),
                    e.getSha256(), e.getBytes(), e.getWidth(), e.getHeight(),
                    e.getCapturedAt(), e.getWindowStart(), e.getWindowEnd(),
                    e.getCoverageJson(), e.getError(), e.getCreatedAt(), e.getExpiresAt());
        }
    }

    public record SnapshotResponse(UUID evidenceId, String status, String provenance,
            boolean idempotentReplay) {
    }

    public record EvidenceUrlResponse(String url, Instant expiresAt) {
    }

    /** Signed playback URL outcome for a recording segment. */
    public record RecordingUrlResponse(String url, Instant expiresAt) {
    }

    public record RecordingSegmentResponse(
            UUID jobId,
            Long attemptId,
            String kind,
            String status,
            int pulseIndex,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant startedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant endedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long bytes,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long durationMs,
            boolean objectDeleted,
            @JsonInclude(JsonInclude.Include.NON_NULL) String error) {

        public static RecordingSegmentResponse from(MonitoringRecordingJob j) {
            return new RecordingSegmentResponse(j.getId(), j.getAttemptId(), j.getKind(),
                    j.getStatus(), j.getPulseIndex(), j.getStartedAt(), j.getEndedAt(),
                    j.getBytes(), j.getDurationMs(), j.isObjectDeleted(), j.getError());
        }
    }

    public record AttemptMediaStatusResponse(
            boolean fullRecordingEnabled,
            boolean rollingBufferActive,
            boolean snapshotsEnabled) {
    }
}
