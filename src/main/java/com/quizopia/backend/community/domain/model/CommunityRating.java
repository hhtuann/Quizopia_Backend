package com.quizopia.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping {@code community_ratings} (ADR-007 §3, V31, issue
 * #65). Composite PK {@code (user_id, publication_id)} (like-entity
 * precedent) plus the 1..5 value backed by
 * {@code chk_community_ratings_rating}. One rating per (user,
 * publication): writes go through the single native
 * {@code INSERT … ON CONFLICT … DO UPDATE SET rating, updated_at
 * RETURNING (xmax = 0)} upsert in {@code CommunitySocialService}
 * (JdbcTemplate; §6 — {@code xmax = 0} distinguishes insert vs update
 * race-correctly); removal is a direct DELETE.
 */
@Entity
@Table(name = "community_ratings")
@IdClass(CommunityRating.RatingId.class)
public class CommunityRating {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "publication_id", nullable = false)
    private Long publicationId;

    @Column(nullable = false)
    private short rating;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityRating() {
    }

    public CommunityRating(Long userId, Long publicationId, short rating) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.publicationId = Objects.requireNonNull(publicationId, "publicationId must not be null");
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        this.rating = rating;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPublicationId() {
        return publicationId;
    }

    public short getRating() {
        return rating;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Composite key ({@code user_id, publication_id}). */
    public static class RatingId implements Serializable {

        private Long userId;
        private Long publicationId;

        public RatingId() {
        }

        public RatingId(Long userId, Long publicationId) {
            this.userId = userId;
            this.publicationId = publicationId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RatingId other)) {
                return false;
            }
            return Objects.equals(userId, other.userId)
                    && Objects.equals(publicationId, other.publicationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, publicationId);
        }
    }
}
