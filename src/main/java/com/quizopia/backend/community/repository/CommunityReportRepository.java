package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityReport;
import com.quizopia.backend.community.domain.model.CommunityReportStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link CommunityReport} (ADR-006 §3 V27 / §7).
 *
 * <p>Submission dedupe uses {@link #insertIfNoOpenReport} — a native
 * {@code INSERT … ON CONFLICT (reporter_user_id, publication_id) WHERE
 * status = 'OPEN' DO NOTHING} with partial-index inference — followed by
 * {@link #findOpenByReporterAndPublication}; a caught constraint
 * violation would abort the enclosing PostgreSQL transaction
 * (roster-membership precedent), so it is never used.
 *
 * <p>Dismiss/resolve load the row with {@link #findForUpdateById} (the
 * only lock — the publication row is NOT locked for report handling).
 */
public interface CommunityReportRepository extends JpaRepository<CommunityReport, Long> {

    /**
     * The §7 dedupe insert: creates a new OPEN report unless an OPEN
     * report already exists for (reporter, publication). Returns 1 when
     * this call created the row, 0 when a conflicting OPEN row exists
     * (pre-existing or committed concurrently).
     */
    @Modifying
    @Query(value = "INSERT INTO community_reports"
            + " (publication_id, reporter_user_id, reason_code, details, status, created_at)"
            + " VALUES (:publicationId, :reporterUserId, :reasonCode, :details, 'OPEN', :createdAt)"
            + " ON CONFLICT (reporter_user_id, publication_id) WHERE status = 'OPEN' DO NOTHING",
            nativeQuery = true)
    int insertIfNoOpenReport(@Param("publicationId") long publicationId,
                             @Param("reporterUserId") long reporterUserId,
                             @Param("reasonCode") String reasonCode,
                             @Param("details") String details,
                             @Param("createdAt") java.time.Instant createdAt);

    /**
     * Re-read after the dedupe insert: exactly one OPEN row can exist
     * per (reporter, publication) — the partial unique index guarantees
     * it. Never absent after {@link #insertIfNoOpenReport} ran.
     */
    @Query("SELECT r FROM CommunityReport r"
            + " WHERE r.reporterUserId = :reporterUserId AND r.publicationId = :publicationId"
            + " AND r.status = :status")
    Optional<CommunityReport> findByReporterAndPublicationAndStatus(
            @Param("reporterUserId") Long reporterUserId,
            @Param("publicationId") Long publicationId,
            @Param("status") CommunityReportStatus status);

    default Optional<CommunityReport> findOpenByReporterAndPublication(
            Long reporterUserId, Long publicationId) {
        return findByReporterAndPublicationAndStatus(
                reporterUserId, publicationId, CommunityReportStatus.OPEN);
    }

    /** Report row lock for the dismiss/resolve transition. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CommunityReport r WHERE r.id = :id")
    Optional<CommunityReport> findForUpdateById(@Param("id") Long id);

    /** Moderator queue (status filter applied by the caller when present). */
    Page<CommunityReport> findByStatus(CommunityReportStatus status, Pageable pageable);

    /** OPEN count for one publication — the owner DTO {@code openReportCount}. */
    long countByPublicationIdAndStatus(Long publicationId, CommunityReportStatus status);

    /** Batched OPEN counts for the owner list (single query, no N+1). */
    @Query("SELECT r.publicationId, COUNT(r) FROM CommunityReport r"
            + " WHERE r.publicationId IN :publicationIds AND r.status = :status"
            + " GROUP BY r.publicationId")
    List<Object[]> countByPublicationIdsAndStatus(
            @Param("publicationIds") List<Long> publicationIds,
            @Param("status") CommunityReportStatus status);
}
