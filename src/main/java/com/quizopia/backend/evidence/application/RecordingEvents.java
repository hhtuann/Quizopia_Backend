package com.quizopia.backend.evidence.application;

import java.util.List;
import java.util.UUID;

/** Spring application events consumed AFTER COMMIT by the coordinator. */
public final class RecordingEvents {

    private RecordingEvents() {
    }

    /**
     * Camera presence changed for a bound attempt (webhook routing, attempt
     * start with an already-connected camera, attempt submit) — the trigger
     * for starting/stopping recording work. Published INSIDE the domain
     * transaction; the coordinator listens AFTER COMMIT only (ADR-008
     * invariant 6: media I/O never extends a transaction).
     */
    public record CameraPresence(
            UUID bindingId,
            long attemptId,
            long examSessionId,
            boolean connected) {
    }

    /** Egress lifecycle webhook projection ({@code egress_*} events). */
    public record EgressUpdate(
            String egressId,
            String status,
            long startedAtNs,
            long endedAtNs,
            String error,
            List<File> files) {

        public record File(String filename, long size, long durationNs,
                long startedAtNs, long endedAtNs) {
        }
    }
}
