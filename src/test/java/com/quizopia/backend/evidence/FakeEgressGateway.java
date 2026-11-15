package com.quizopia.backend.evidence;

import com.quizopia.backend.media.egress.EgressGateway;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link EgressGateway} for coordinator tests: records starts and
 * stops, returns canned {@code EgressInfo}, and can simulate failures.
 * Registered via {@code MediaGatewayTestConfig}-style test configuration.
 */
public class FakeEgressGateway implements EgressGateway {

    public final List<String> started = new ArrayList<>();
    public final List<String> stopped = new ArrayList<>();
    public final Map<String, EgressInfo> infos = new LinkedHashMap<>();
    private int counter;
    public RuntimeException startFailure;
    public RuntimeException stopFailure;

    @Override
    public EgressRun startVideoTrackRecording(String roomName, String videoTrackSid,
            String filepathPrefix, S3Output s3) {
        if (startFailure != null) {
            throw startFailure;
        }
        String egressId = "EG_FAKE_" + (++counter);
        started.add(egressId + "|" + roomName + "|" + videoTrackSid + "|" + filepathPrefix);
        infos.put(egressId, new EgressInfo(egressId, "EGRESS_ACTIVE", 0, 0, null, List.of()));
        return new EgressRun(egressId, "EGRESS_ACTIVE");
    }

    @Override
    public void stop(String egressId) {
        if (stopFailure != null) {
            throw stopFailure;
        }
        stopped.add(egressId);
    }

    @Override
    public List<EgressInfo> list(String roomName, boolean activeOnly) {
        return List.copyOf(infos.values());
    }

    public void completeWithFile(String egressId, String filename, long size, long durationNs,
            long startedAtNs, long endedAtNs) {
        infos.put(egressId, new EgressInfo(egressId, "EGRESS_COMPLETE", startedAtNs, endedAtNs,
                null, List.of(new EgressFile(filename, size, durationNs, startedAtNs, endedAtNs))));
    }

    public void markFailed(String egressId, String error) {
        infos.put(egressId, new EgressInfo(egressId, "EGRESS_FAILED", 0, 0, error, List.of()));
    }
}
