package com.quizopia.backend.monitoring.repository;

import com.quizopia.backend.monitoring.domain.model.MonitoringAlertReview;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the V42 append-only review ledger (P4-E, issue #96).
 *
 * <p>All writes go through {@code AlertReviewService.save} (single
 * transaction: optimistic-concurrency check → ledger append → denormalized
 * alert update → timeline event). Reads are the history listing and the
 * idempotency probe.
 */
public interface MonitoringAlertReviewRepository extends JpaRepository<MonitoringAlertReview, Long> {

    Optional<MonitoringAlertReview> findByIdempotencyKey(UUID idempotencyKey);

    List<MonitoringAlertReview> findByAlertIdOrderByReviewVersionAsc(Long alertId);

    long countByAlertId(Long alertId);

    /** DISTINCT alerts of a session that have at least one review row. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(DISTINCT r.alertId)
            FROM MonitoringAlertReview r
            WHERE r.alertId IN (
                SELECT a.id FROM MonitoringAiAlert a
                WHERE a.attemptId = :attemptId)
            """)
    long countDistinctReviewedAlertsOfAttempt(Long attemptId);
}
