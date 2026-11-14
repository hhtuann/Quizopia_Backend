package com.quizopia.backend.monitoring.repository;

import com.quizopia.backend.monitoring.domain.model.MonitoringMediaRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;

/**
 * Repository for {@link MonitoringMediaRoom} (V37
 * {@code monitoring_media_rooms}). Rows are created lazily by the
 * conflict-proof upsert below — never via {@code save}.
 */
public interface MonitoringMediaRoomRepository extends JpaRepository<MonitoringMediaRoom, Long> {

    /**
     * Lazy room creation: inserts a candidate opaque room name unless this
     * session already has a room. Returns 1 when the row was created, 0 when
     * a room already existed — callers ALWAYS read back the canonical name via
     * {@link #findById(Object)} so concurrent creators agree on one room.
     * Must run inside a transaction.
     */
    @Modifying
    @Query(value = "INSERT INTO monitoring_media_rooms (exam_session_id, room_name, created_at) "
            + "VALUES (:sessionId, :roomName, :at) "
            + "ON CONFLICT (exam_session_id) DO NOTHING",
            nativeQuery = true)
    int insertIgnore(@Param("sessionId") long sessionId,
                     @Param("roomName") String roomName,
                     @Param("at") Timestamp at);
}
