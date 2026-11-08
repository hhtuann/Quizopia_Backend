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
 * JPA entity mapping {@code community_fork_records} (ADR-006 §3 V29 /
 * §7-§8, issue #47). A row is the fact "actor X forked publication Y
 * into target exam Z at T" — immutable after insert: no setter exists
 * and no UPDATE path writes the table. The repository surface exposes
 * save + reads only (append-only, like the moderation audit).
 *
 * <p>{@code idempotencyKeyHash} is the server-keyed HMAC of
 * {@code community-fork:<actor>:<key>} — the plaintext
 * {@code Idempotency-Key} header value is never persisted. The UNIQUE
 * (actor_user_id, idempotency_key_hash) pair makes a retried fork map
 * to exactly one target exam; UNIQUE (target_exam_id) makes one target
 * per fork. All FKs RESTRICT (§3 policy).
 */
@Entity
@Table(name = "community_fork_records")
public class CommunityForkRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @Column(name = "idempotency_key_hash", nullable = false, updatable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "source_publication_id", nullable = false, updatable = false)
    private Long sourcePublicationId;

    @Column(name = "source_exam_id", nullable = false, updatable = false)
    private Long sourceExamId;

    @Column(name = "target_exam_id", nullable = false, updatable = false)
    private Long targetExamId;

    @Column(name = "source_title_snapshot", updatable = false, length = 255)
    private String sourceTitleSnapshot;

    @Column(name = "source_alias_snapshot", updatable = false, length = 80)
    private String sourceAliasSnapshot;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunityForkRecord() {
    }

    public CommunityForkRecord(Long actorUserId, String idempotencyKeyHash,
            Long sourcePublicationId, Long sourceExamId, Long targetExamId,
            String sourceTitleSnapshot, String sourceAliasSnapshot) {
        this.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        this.idempotencyKeyHash = Objects.requireNonNull(idempotencyKeyHash,
                "idempotencyKeyHash must not be null");
        this.sourcePublicationId = Objects.requireNonNull(sourcePublicationId,
                "sourcePublicationId must not be null");
        this.sourceExamId = Objects.requireNonNull(sourceExamId, "sourceExamId must not be null");
        this.targetExamId = Objects.requireNonNull(targetExamId, "targetExamId must not be null");
        this.sourceTitleSnapshot = sourceTitleSnapshot;
        this.sourceAliasSnapshot = sourceAliasSnapshot;
    }

    public Long getId() {
        return id;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getIdempotencyKeyHash() {
        return idempotencyKeyHash;
    }

    public Long getSourcePublicationId() {
        return sourcePublicationId;
    }

    public Long getSourceExamId() {
        return sourceExamId;
    }

    public Long getTargetExamId() {
        return targetExamId;
    }

    public String getSourceTitleSnapshot() {
        return sourceTitleSnapshot;
    }

    public String getSourceAliasSnapshot() {
        return sourceAliasSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
