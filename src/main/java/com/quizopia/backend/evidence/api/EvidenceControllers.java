package com.quizopia.backend.evidence.api;

import com.quizopia.backend.evidence.application.ClipService;
import com.quizopia.backend.evidence.application.EvidenceQueryService;
import com.quizopia.backend.evidence.application.EvidenceQueryService.AttemptMediaStatus;
import com.quizopia.backend.evidence.application.EvidenceQueryService.SignedUrl;
import com.quizopia.backend.evidence.application.EvidenceRetentionJob;
import com.quizopia.backend.evidence.application.EvidenceSnapshotService;
import com.quizopia.backend.evidence.domain.MonitoringEvidence;
import com.quizopia.backend.evidence.dto.EvidenceDtos;
import com.quizopia.backend.evidence.dto.EvidenceDtos.AttemptMediaStatusResponse;
import com.quizopia.backend.evidence.dto.EvidenceDtos.EvidenceResponse;
import com.quizopia.backend.evidence.dto.EvidenceDtos.EvidenceUrlResponse;
import com.quizopia.backend.evidence.dto.EvidenceDtos.RecordingSegmentResponse;
import com.quizopia.backend.evidence.dto.EvidenceDtos.SnapshotResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * P4-D REST surface (issues #91–#94). Authorization is service-layer
 * (attempt ownership / MONITORING_EVIDENCE_READ + session ownership),
 * mirroring the P4-B/C monitoring controllers; the security chain only
 * authenticates JWTs on {@code /api/attempts/**} and
 * {@code /api/exam-sessions/**}.
 */
@RestController
public class EvidenceControllers {

    private final EvidenceSnapshotService snapshots;
    private final ClipService clips;
    private final EvidenceQueryService queries;
    private final EvidenceRetentionJob retention;

    public EvidenceControllers(EvidenceSnapshotService snapshots, ClipService clips,
            EvidenceQueryService queries, EvidenceRetentionJob retention) {
        this.snapshots = snapshots;
        this.clips = clips;
        this.queries = queries;
        this.retention = retention;
    }

    // ------------------------------------------------------------------
    // D1 (#91): student snapshot upload + student media status
    // ------------------------------------------------------------------

    @PostMapping("/api/attempts/{attemptId}/evidence/snapshots")
    public ResponseEntity<SnapshotResponse> uploadSnapshot(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("attemptId") Long attemptId,
            @RequestParam("alertId") Long alertId,
            @RequestParam("idempotencyKey") String idempotencyKey,
            @RequestParam("capturedAt") Long capturedAt,
            @RequestPart("file") MultipartFile file) throws java.io.IOException {
        long userId = Long.parseLong(jwt.getSubject());
        // Cheap pre-guard: reject oversized parts BEFORE reading them into
        // memory (the multipart layer still allows the 5 MiB workbook limit).
        if (file.getSize() > 2 * 1024 * 1024) {
            throw new com.quizopia.backend.monitoring.exception.MonitoringException(
                    com.quizopia.backend.monitoring.exception.MonitoringErrorCode
                            .EVIDENCE_SNAPSHOT_REJECTED, "SNAPSHOT_TOO_LARGE");
        }
        EvidenceSnapshotService.UploadOutcome outcome = snapshots.upload(
                userId, attemptId, alertId, idempotencyKey, capturedAt, file.getBytes());
        MonitoringEvidence row = outcome.row();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SnapshotResponse(row.getId(), row.getStatus(),
                        row.getProvenance(), outcome.idempotentReplay()));
    }

    @GetMapping("/api/attempts/{attemptId}/monitoring/media-status")
    public ResponseEntity<AttemptMediaStatusResponse> mediaStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("attemptId") Long attemptId) {
        long userId = Long.parseLong(jwt.getSubject());
        AttemptMediaStatus status = queries.mediaStatus(userId, attemptId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AttemptMediaStatusResponse(status.fullRecordingEnabled(),
                        status.rollingBufferActive(), status.snapshotsEnabled()));
    }

    // ------------------------------------------------------------------
    // D3/D4 (#93/#94): teacher evidence, clips, signed playback
    // ------------------------------------------------------------------

    @GetMapping("/api/exam-sessions/{sessionId}/evidence")
    public ResponseEntity<List<EvidenceResponse>> listEvidence(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("sessionId") Long sessionId,
            @RequestParam(value = "alertId", required = false) Long alertId,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        long userId = Long.parseLong(jwt.getSubject());
        List<EvidenceResponse> items = queries.listEvidence(userId, sessionId, alertId, kind, limit)
                .stream().map(EvidenceResponse::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(items);
    }

    @PostMapping("/api/exam-sessions/{sessionId}/ai-alerts/{alertId}/clip")
    public ResponseEntity<EvidenceResponse> requestClip(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("sessionId") Long sessionId,
            @PathVariable("alertId") Long alertId) {
        long userId = Long.parseLong(jwt.getSubject());
        MonitoringEvidence clip = clips.requestClip(userId, sessionId, alertId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(EvidenceResponse.from(clip));
    }

    @GetMapping("/api/exam-sessions/{sessionId}/evidence/{evidenceId}/url")
    public ResponseEntity<EvidenceUrlResponse> playbackUrl(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("sessionId") Long sessionId,
            @PathVariable("evidenceId") UUID evidenceId) {
        long userId = Long.parseLong(jwt.getSubject());
        SignedUrl signed = queries.mintPlaybackUrl(userId, sessionId, evidenceId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new EvidenceUrlResponse(signed.url(), signed.expiresAt()));
    }

    @GetMapping("/api/exam-sessions/{sessionId}/recordings/{jobId}/url")
    public ResponseEntity<EvidenceDtos.RecordingUrlResponse> recordingPlaybackUrl(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("sessionId") Long sessionId,
            @PathVariable("jobId") java.util.UUID jobId) {
        long userId = Long.parseLong(jwt.getSubject());
        var signed = queries.recordingPlaybackUrl(userId, sessionId, jobId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new EvidenceDtos.RecordingUrlResponse(signed.url(), signed.expiresAt()));
    }

    @GetMapping("/api/exam-sessions/{sessionId}/recordings")
    public ResponseEntity<List<RecordingSegmentResponse>> recordings(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("sessionId") Long sessionId) {
        long userId = Long.parseLong(jwt.getSubject());
        List<RecordingSegmentResponse> segments = queries.recordings(userId, sessionId)
                .stream().map(RecordingSegmentResponse::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(segments);
    }

    /** Manual demo purge (issue #94 demo hygiene): deletes live objects,
     *  keeps metadata as EXPIRED + the audit trail. */
    @DeleteMapping("/api/exam-sessions/{sessionId}/evidence")
    public ResponseEntity<Integer> purge(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("sessionId") Long sessionId) {
        long userId = Long.parseLong(jwt.getSubject());
        // ownership re-check via the same gate as reads
        queries.listEvidence(userId, sessionId, null, "SNAPSHOT", 1);
        int deleted = retention.purgeSession(sessionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deleted);
    }
}
