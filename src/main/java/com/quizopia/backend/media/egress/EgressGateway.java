package com.quizopia.backend.media.egress;

import java.util.List;

/**
 * LiveKit Egress port (P4-D, ADR-009 §2.2). Implemented over the
 * {@code livekit.Egress} Twirp service served by the pinned
 * {@code livekit-server v1.9.12} (protocol @3688e156): the unified
 * {@code StartEgress} RPC does NOT exist at this pin, so recordings use
 * {@code StartTrackCompositeEgress} with {@code video_track_id} only —
 * video-only by construction (no {@code audio_track_id} is ever sent).
 *
 * <p>Implementations MUST NOT be called inside a DB transaction
 * (ADR-008 invariant 6).
 */
public interface EgressGateway {

    /** Inline S3/MinIO output config handed to the egress request per run —
     *  credentials are never persisted in the egress config file. */
    record S3Output(String endpoint, String accessKey, String secretKey,
                    String bucket, String region, boolean forcePathStyle) {
    }

    /** A started egress run (echo of the twirp {@code EgressInfo}). */
    record EgressRun(String egressId, String status) {
    }

    /** Authoritative file result of a finished egress run. */
    record EgressFile(String filename, long size, long durationNs,
                      long startedAtNs, long endedAtNs) {
    }

    /** Full {@code EgressInfo} projection (webhook/ListEgress shaped). */
    record EgressInfo(String egressId, String status, long startedAtNs, long endedAtNs,
                      String error, List<EgressFile> fileResults) {

        public boolean isTerminal() {
            return "EGRESS_COMPLETE".equals(status) || "EGRESS_FAILED".equals(status)
                    || "EGRESS_ABORTED".equals(status) || "EGRESS_LIMIT_REACHED".equals(status);
        }
    }

    /**
     * Starts a video-only MP4 track recording into the configured S3 output.
     *
     * @param filepathPrefix egress filepath template prefix (without
     *                       extension); the egress appends {@code -{time}.mp4}
     */
    EgressRun startVideoTrackRecording(String roomName, String videoTrackSid,
            String filepathPrefix, S3Output s3);

    /** Stops a run; the egress finalizes and uploads the MP4. */
    void stop(String egressId);

    /** Lists egress runs of a room (active only when {@code activeOnly}). */
    List<EgressInfo> list(String roomName, boolean activeOnly);
}
