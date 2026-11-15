package com.quizopia.backend.evidence.repository;

import com.quizopia.backend.evidence.domain.MonitoringEvidenceAccess;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Evidence access audit ledger (P4-D #94; 90 d retention, job-swept). */
public interface MonitoringEvidenceAccessRepository
        extends JpaRepository<MonitoringEvidenceAccess, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM monitoring_evidence_access WHERE accessed_at < :cutoff",
            nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
