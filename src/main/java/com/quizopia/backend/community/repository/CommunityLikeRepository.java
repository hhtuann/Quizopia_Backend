package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for {@link CommunityLike} (ADR-007 §3/§6, issue #65).
 *
 * <p>The like path is the §6 idempotent upsert:
 * {@link #insertIfAbsent} — a native
 * {@code INSERT … ON CONFLICT (user_id, publication_id) DO NOTHING} —
 * with {@code created} taken from the affected-rowcount (bookmark
 * precedent); concurrent PUTs of the same (user, publication) collapse
 * onto one row with no loser, never a caught constraint violation.
 * {@link #countByPublicationId} serves the on-read {@code likeCount}
 * aggregate through {@code idx_community_likes_publication}
 * (index-only COUNT).
 */
public interface CommunityLikeRepository extends JpaRepository<CommunityLike, CommunityLike.LikeId> {

    /**
     * The §6 idempotent like: creates the row unless one already exists
     * for (user, publication). Returns 1 when this call created the row
     * (→ 201), 0 when a row already existed (→ 200).
     */
    @Modifying
    @Query(value = "INSERT INTO community_likes (user_id, publication_id, created_at)"
            + " VALUES (:userId, :publicationId, :createdAt)"
            + " ON CONFLICT (user_id, publication_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId,
                       @Param("publicationId") long publicationId,
                       @Param("createdAt") Instant createdAt);

    /** The caller's own row, for {@code liked} in the social summary. */
    Optional<CommunityLike> findByUserIdAndPublicationId(Long userId, Long publicationId);

    /** Idempotent unlike by owner key; returns affected rows (0 = was absent). */
    @Modifying
    @Query(value = "DELETE FROM community_likes"
            + " WHERE user_id = :userId AND publication_id = :publicationId", nativeQuery = true)
    int deleteByUserIdAndPublicationId(@Param("userId") long userId,
                                       @Param("publicationId") long publicationId);

    /** likeCount aggregate (index-only via idx_community_likes_publication). */
    long countByPublicationId(Long publicationId);
}
