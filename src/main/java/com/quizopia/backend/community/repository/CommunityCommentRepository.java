package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityComment;
import com.quizopia.backend.community.domain.model.CommunityCommentState;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link CommunityComment} (ADR-007 §3/§6, V31, #66).
 *
 * <p>Owner writes are the §6 single-statement CAS shapes: PATCH is the
 * state-guarded {@link #updateBodyIfVisible} (rowcount 0 on an owned
 * row = state conflict → 409, never a resurrected tombstone) and DELETE
 * is the idempotent tombstone {@link #deleteTombstoneByOwner} over any
 * state (rowcount 0 without ownership → 404, else 200; body cleared,
 * {@code edited_at} preserved). No separate row lock is needed for
 * either — the guarded UPDATE is the serialization point.
 *
 * <p>The public page scan is the single-table
 * {@link #findByPublicationIdAndStateOrderByCreatedAtDescIdDesc} on the
 * frozen {@code (publication_id, state, created_at DESC, id DESC)}
 * index — page + count, no N+1 (the alias is denormalized on the row).
 */
public interface CommunityCommentRepository extends JpaRepository<CommunityComment, Long> {

    /** Public page: VISIBLE rows of one publication, newest-first, deterministic id tiebreak. */
    Page<CommunityComment> findByPublicationIdAndStateOrderByCreatedAtDescIdDesc(
            Long publicationId, CommunityCommentState state, Pageable pageable);

    /**
     * The §6 owner PATCH CAS: rewrites the body and stamps
     * {@code edited_at} only while the row is still the caller's AND
     * VISIBLE. Returns 1 on success; 0 means foreign/absent (404) or a
     * non-VISIBLE own row (409) — disambiguated by
     * {@link #existsByIdAndAuthorUserId}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE community_comments"
            + " SET body = :body, edited_at = now(), updated_at = now()"
            + " WHERE id = :id AND author_user_id = :userId AND state = 'VISIBLE'",
            nativeQuery = true)
    int updateBodyIfVisible(@Param("id") long id,
                            @Param("userId") long userId,
                            @Param("body") String body);

    /**
     * The §6 owner DELETE tombstone: soft-deletes over ANY state (users
     * keep control of their own UGC), clears the body and preserves
     * {@code edited_at}. Idempotent; rowcount 0 without ownership → 404.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE community_comments"
            + " SET state = 'DELETED', body = '', updated_at = now()"
            + " WHERE id = :id AND author_user_id = :userId", nativeQuery = true)
    int deleteTombstoneByOwner(@Param("id") long id, @Param("userId") long userId);

    /**
     * Ownership disambiguation after a 0-rowcount PATCH CAS: the row
     * exists and is the caller's (→ 409 state conflict) or it does not
     * (→ 404 anti-enumeration, byte-identical to a foreign id).
     */
    boolean existsByIdAndAuthorUserId(Long id, Long authorUserId);

    /** Comment row lock for the moderator hide/restore transition (§5/§8). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CommunityComment c WHERE c.id = :id")
    java.util.Optional<CommunityComment> findForUpdateById(@Param("id") Long id);
}
