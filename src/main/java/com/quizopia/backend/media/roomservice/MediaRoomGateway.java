package com.quizopia.backend.media.roomservice;

import java.util.List;
import java.util.Optional;

/**
 * LiveKit RoomService port (P4-B, issues #84–#87). Implementations MUST NOT
 * be called while a database transaction is active — the ADR-08 rule that
 * no external media call ever waits on (or extends) a Session/Attempt
 * transaction. {@link TwirpMediaRoomGateway} enforces this at runtime.
 */
public interface MediaRoomGateway {

    /** Fetch one participant (with tracks); empty when room/participant absent. */
    Optional<LkParticipant> findParticipant(String room, String identity);

    /** List connected participants of a room; empty list when the room does not exist. */
    List<LkParticipant> listParticipants(String room);

    /** Remove a participant from a room (kick). False when not a member. */
    boolean removeParticipant(String room, String identity);

    /** Delete a room (best-effort cleanup at session close). */
    boolean deleteRoom(String room);
}
