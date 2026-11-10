package com.quizopia.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping {@code community_comments} (ADR-007 §3/§5/§6,
 * V31, issue #66; ADR-007 Rev 4 / #72). IDENTITY id (single-column PK
 * — no {@code @IdClass}). {@code author_alias} is the RETIRED V31
 * alias snapshot: legacy rows keep their historical value (audit),
 * new rows store NULL (V32 dropped the NOT NULL) and the public
 * author name is resolved from the author's CURRENT
 * {@code users.display_name} at read time.
 *
 * <p>Writes go through the repository's state-guarded CAS statements
 * (§6): owner PATCH is {@code UPDATE … WHERE state='VISIBLE'} (0 rows
 * owned → 409 COMMUNITY_COMMENT_STATE_INVALID), owner DELETE is the
 * tombstone {@code UPDATE … SET state='DELETED', body=''} over any
 * state, and moderator hide/restore mutate the locked entity
 * (publication hide/restore precedent). The entity therefore exposes
 * only the two moderator transitions; owners have no entity mutators.
 */
@Entity
@Table(name = "community_comments")
public class CommunityComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "publication_id", nullable = false, updatable = false)
    private Long publicationId;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private Long authorUserId;

    @Column(name = "author_alias", length = 80, updatable = false)
    private String authorAlias;

    @Column(nullable = false, length = 2000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommunityCommentState state = CommunityCommentState.VISIBLE;

    @Column(name = "edited_at")
    private Instant editedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityComment() {
    }

    public CommunityComment(Long publicationId, Long authorUserId, String body) {
        this.publicationId = Objects.requireNonNull(publicationId, "publicationId must not be null");
        this.authorUserId = Objects.requireNonNull(authorUserId, "authorUserId must not be null");
        this.body = Objects.requireNonNull(body, "body must not be null");
    }

    /** VISIBLE → HIDDEN under the comment row lock (moderator only). */
    public void hideByModeration() {
        requireState(CommunityCommentState.VISIBLE, "hide");
        this.state = CommunityCommentState.HIDDEN;
    }

    /** HIDDEN → VISIBLE under the comment row lock (moderator only). */
    public void restoreByModeration() {
        requireState(CommunityCommentState.HIDDEN, "restore");
        this.state = CommunityCommentState.VISIBLE;
    }

    private void requireState(CommunityCommentState expected, String transition) {
        if (state != expected) {
            throw new IllegalStateException(
                    transition + " requires a " + expected + " comment, was " + state);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPublicationId() {
        return publicationId;
    }

    public Long getAuthorUserId() {
        return authorUserId;
    }

    public String getBody() {
        return body;
    }

    public CommunityCommentState getState() {
        return state;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
