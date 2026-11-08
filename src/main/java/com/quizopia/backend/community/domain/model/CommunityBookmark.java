package com.quizopia.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping {@code community_bookmarks} (ADR-006 §3 V28 / §7,
 * issue #45). A row is the fact "user X saved publication Y at T" —
 * immutable after insert: no setter exists, no UPDATE path writes the
 * table. Creation goes through the repository's native
 * {@code INSERT … ON CONFLICT (user_id, publication_id) DO NOTHING}
 * upsert (idempotent under concurrency); removal is a direct DELETE.
 *
 * <p>The UNIQUE(user_id, publication_id) constraint backs the conflict
 * target; {@code idx_community_bookmarks_owner} serves the paged
 * {@code created_at DESC, id DESC} owner scan.
 */
@Entity
@Table(name = "community_bookmarks")
public class CommunityBookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "publication_id", nullable = false, updatable = false)
    private Long publicationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunityBookmark() {
    }

    public CommunityBookmark(Long userId, Long publicationId) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.publicationId = Objects.requireNonNull(publicationId, "publicationId must not be null");
    }

    public Long getId() {
        return id;
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
}
