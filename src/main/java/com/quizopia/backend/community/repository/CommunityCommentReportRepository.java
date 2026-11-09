package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityCommentReport;
import com.quizopia.backend.community.domain.model.CommunityReportStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link CommunityCommentReport} (ADR-007 §3/§5, V31,
 * #66) — the {@code CommunityReportRepository} (#42) shape, scoped to
 * comments.
 *
 * <p>Submission dedupe uses {@link #insertIfNoOpenCommentReport} — a
 * native {@code INSERT … ON CONFLICT (reporter_user_id, comment_id)
 * WHERE status = 'OPEN' DO NOTHING} with partial-index inference —
 * followed by {@link #findOpenByReporterAndComment}; a caught
 * constraint violation would abort the enclosing PostgreSQL
 * transaction, so it is never used. Dismiss/resolve load the row with
 * {@link #findForUpdateById} (the only lock — neither the comment nor
 * the publication row is locked for report handling).
 */
public interface CommunityCommentReportRepository extends JpaRepository<CommunityCommentReport, Long> {

    /**
     * The §5 dedupe insert: creates a new OPEN report unless an OPEN
     * report already exists for (reporter, comment). Returns 1 when
     * this call created the row, 0 when a conflicting OPEN row exists
     * (pre-existing or committed concurrently).
     */
    @Modifying
    @Query(value = "INSERT INTO community_comment_reports"
            + " (comment_id, reporter_user_id, reason_code, details, status, created_at)"
            + " VALUES (:commentId, :reporterUserId, :reasonCode, :details, 'OPEN', :createdAt)"
            + " ON CONFLICT (reporter_user_id, comment_id) WHERE status = 'OPEN' DO NOTHING",
            nativeQuery = true)
    int insertIfNoOpenCommentReport(@Param("commentId") long commentId,
                                    @Param("reporterUserId") long reporterUserId,
                                    @Param("reasonCode") String reasonCode,
                                    @Param("details") String details,
                                    @Param("createdAt") java.time.Instant createdAt);

    /**
     * Re-read after the dedupe insert: exactly one OPEN row can exist
     * per (reporter, comment) — the partial unique index guarantees it.
     */
    @Query("SELECT r FROM CommunityCommentReport r"
            + " WHERE r.reporterUserId = :reporterUserId AND r.commentId = :commentId"
            + " AND r.status = :status")
    Optional<CommunityCommentReport> findByReporterAndCommentAndStatus(
            @Param("reporterUserId") Long reporterUserId,
            @Param("commentId") Long commentId,
            @Param("status") CommunityReportStatus status);

    default Optional<CommunityCommentReport> findOpenByReporterAndComment(
            Long reporterUserId, Long commentId) {
        return findByReporterAndCommentAndStatus(
                reporterUserId, commentId, CommunityReportStatus.OPEN);
    }

    /** Comment-report row lock for the dismiss/resolve transition. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CommunityCommentReport r WHERE r.id = :id")
    Optional<CommunityCommentReport> findForUpdateById(@Param("id") Long id);

    /** Moderator queue (status filter applied by the caller when present). */
    Page<CommunityCommentReport> findByStatus(CommunityReportStatus status, Pageable pageable);
}
