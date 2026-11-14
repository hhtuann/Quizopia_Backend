package com.quizopia.backend.monitoring;

import com.quizopia.backend.media.roomservice.LkParticipant;
import com.quizopia.backend.media.roomservice.LkTrack;
import com.quizopia.backend.media.roomservice.MediaRoomGateway;
import com.quizopia.backend.media.roomservice.TwirpMediaRoomGateway;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link MediaRoomGateway} for the P4-B camera-preflight tests.
 * Deliberately does NOT enforce the no-transaction guard (the fake is pure
 * state) — that guard is covered for the real gateway by
 * {@code TwirpMediaRoomGatewayTransactionGuardTest}.
 *
 * <p>State is mutable so tests can simulate the LiveKit room: publish or
 * unpublish camera tracks, disconnect participants and inject
 * {@link TwirpMediaRoomGateway.LiveKitUnavailableException} failures.
 */
public class FakeMediaRoomGateway implements MediaRoomGateway {

    /** room + "/" + identity → participant. */
    final Map<String, LkParticipant> participants = new ConcurrentHashMap<>();

    /** room + "/" + identity of every removeParticipant call. */
    final List<String> removed = new CopyOnWriteArrayList<>();

    /** When non-null, every gateway call throws it (infra outage). */
    volatile RuntimeException failure;

    /** Simulates a participant connected with an unmuted camera video track. */
    public void publishCamera(String room, String identity, String trackSid) {
        participants.put(key(room, identity), new LkParticipant(identity, "ACTIVE",
                List.of(new LkTrack(trackSid, "VIDEO", "CAMERA", false))));
    }

    /** Simulates track mute/publish-stop: participant present, camera muted. */
    public void muteCamera(String room, String identity) {
        participants.put(key(room, identity), new LkParticipant(identity, "ACTIVE",
                List.of(new LkTrack("track-muted", "VIDEO", "CAMERA", true))));
    }

    /** Simulates the participant leaving the room entirely. */
    public void disconnect(String room, String identity) {
        participants.remove(key(room, identity));
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void reset() {
        participants.clear();
        removed.clear();
        failure = null;
    }

    @Override
    public Optional<LkParticipant> findParticipant(String room, String identity) {
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(participants.get(key(room, identity)));
    }

    @Override
    public List<LkParticipant> listParticipants(String room) {
        if (failure != null) {
            throw failure;
        }
        return participants.entrySet().stream()
                .filter(e -> e.getKey().startsWith(room + "/"))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(LkParticipant::identity))
                .toList();
    }

    @Override
    public boolean removeParticipant(String room, String identity) {
        if (failure != null) {
            throw failure;
        }
        removed.add(key(room, identity));
        return participants.remove(key(room, identity)) != null;
    }

    @Override
    public boolean deleteRoom(String room) {
        if (failure != null) {
            throw failure;
        }
        boolean hadAny = !listParticipants(room).isEmpty();
        listParticipants(room).forEach(p -> participants.remove(key(room, p.identity())));
        return true;
    }

    private static String key(String room, String identity) {
        return room + "/" + identity;
    }
}
