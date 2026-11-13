package com.quizopia.backend.exam.repository;

import com.quizopia.backend.exam.domain.model.SessionMonitoringPolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionMonitoringPolicyRepository extends JpaRepository<SessionMonitoringPolicy, Long> {

    /** The policy of a session; empty = legacy session created before P4-A (all OFF). */
    Optional<SessionMonitoringPolicy> findByExamSessionId(Long examSessionId);

    /**
     * Lightweight existence probe used by the fail-closed monitored-start gate
     * (P4-A/P4-B boundary): true only when a policy row exists AND monitoring
     * is enabled.
     */
    @Query("SELECT CASE WHEN p.monitoringEnabled = true THEN true ELSE false END "
            + "FROM SessionMonitoringPolicy p WHERE p.examSessionId = :examSessionId")
    Boolean isMonitoringEnabled(@Param("examSessionId") Long examSessionId);
}
