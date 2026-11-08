package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for {@link CommunityBookmark} (ADR-006 §3 V28 / §7).
 *
 * <p>The save path is the §7 idempotent upsert:
 * {@link #insertIfAbsent} — a native
 * {@code INSERT … ON CONFLICT (user_id, publication_id) DO NOTHING} —
 * followed by {@link #findByUserIdAndPublicationId}; a caught
 * constraint violation would abort the enclosing PostgreSQL transaction
 * (roster-membership precedent), so it is never used. Concurrent saves
 * of the same (user, publication) collapse onto one row with no loser.
 *
 * <p>Removal is a direct DELETE on the (user, publication) key —
 * idempotent by shape (0 affected rows is a normal outcome). The paged
 * owner scan with §4-availability lives in
 * {@code CommunityVisibilityStore#findBookmarkedPage} (single SQL, no
 * N+1), not here.
 */
public interface CommunityBookmarkRepository extends JpaRepository<CommunityBookmark, Long> {

    /**
     * The §7 idempotent save: creates the bookmark unless one already
     * exists for (user, publication). Returns 1 when this call created
     * the row (→ 201), 0 when a row already exists (→ 200 with that
     * row, same id and savedAt either way).
     */
    @Modifying
    @Query(value = "INSERT INTO community_bookmarks (user_id, publication_id, created_at)"
            + " VALUES (:userId, :publicationId, :createdAt)"
            + " ON CONFLICT (user_id, publication_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId,
                       @Param("publicationId") long publicationId,
                       @Param("createdAt") Instant createdAt);

    /** Re-select after the upsert; never absent once {@link #insertIfAbsent} ran. */
    Optional<CommunityBookmark> findByUserIdAndPublicationId(Long userId, Long publicationId);

    /** Idempotent removal by owner key; returns affected rows (0 = was absent). */
    @Modifying
    @Query(value = "DELETE FROM community_bookmarks"
            + " WHERE user_id = :userId AND publication_id = :publicationId", nativeQuery = true)
    int deleteByUserIdAndPublicationId(@Param("userId") long userId,
                                       @Param("publicationId") long publicationId);
}
