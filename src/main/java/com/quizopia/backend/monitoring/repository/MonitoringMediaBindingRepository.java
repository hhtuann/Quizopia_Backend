package com.quizopia.backend.monitoring.repository;

import com.quizopia.backend.monitoring.domain.model.MonitoringMediaBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link MonitoringMediaBinding} (V37
 * {@code monitoring_media_bindings}).
 */
public interface MonitoringMediaBindingRepository extends JpaRepository<MonitoringMediaBinding, UUID> {

    /** The binding created with the given preflight (1:1 by construction). */
    Optional<MonitoringMediaBinding> findByPreflightId(UUID preflightId);

    /** Bindings of a session in epoch order — the teacher camera-status base set. */
    List<MonitoringMediaBinding> findByExamSessionIdOrderByActiveFromAscIdAsc(Long examSessionId);

    /**
     * The binding of a media identity within a session, highest epoch first —
     * the teacher revoke lookup. Identity is a per-preflight opaque UUID, so
     * in practice the list has at most one element.
     */
    List<MonitoringMediaBinding> findByExamSessionIdAndMediaIdentityOrderByEpochDesc(
            Long examSessionId, String mediaIdentity);

    /** Latest binding (any state) attached to an attempt — token renewal lookup. */
    Optional<MonitoringMediaBinding> findFirstByAttemptIdOrderByEpochDesc(Long attemptId);

    /** Highest epoch already used by (session, user); empty when none. */
    @Query("SELECT MAX(b.epoch) FROM MonitoringMediaBinding b "
            + "WHERE b.examSessionId = :sessionId AND b.userId = :userId")
    Optional<Integer> findMaxEpoch(@Param("sessionId") Long sessionId, @Param("userId") Long userId);
}
