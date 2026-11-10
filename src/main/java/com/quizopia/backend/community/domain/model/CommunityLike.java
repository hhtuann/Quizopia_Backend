package com.quizopia.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping {@code community_likes} (ADR-007 §3, V31, issue
 * #65). Composite PK {@code (user_id, publication_id)} — the
 * publication-tags {@code @IdClass} precedent; the conflict arbiter for
 * the native upsert is identical to #64's literal UNIQUE pair. A row is
 * the immutable fact "user X liked publication Y at T": no setter, no
 * UPDATE path writes the table. Creation goes through the repository's
 * {@code INSERT … ON CONFLICT (user_id, publication_id) DO NOTHING}
 * upsert (idempotent under concurrency); removal is a direct DELETE.
 */
@Entity
@Table(name = "community_likes")
@IdClass(CommunityLike.LikeId.class)
public class CommunityLike {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "publication_id", nullable = false)
    private Long publicationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunityLike() {
    }

    public CommunityLike(Long userId, Long publicationId) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.publicationId = Objects.requireNonNull(publicationId, "publicationId must not be null");
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPublicationId() {
        return publicationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Composite key ({@code user_id, publication_id}). */
    public static class LikeId implements Serializable {

        private Long userId;
        private Long publicationId;

        public LikeId() {
        }

        public LikeId(Long userId, Long publicationId) {
            this.userId = userId;
            this.publicationId = publicationId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LikeId other)) {
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
