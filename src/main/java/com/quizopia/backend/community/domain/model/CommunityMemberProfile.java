package com.quizopia.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping {@code community_member_profiles} (ADR-007 §3/§4,
 * V31). The community-owned pseudonym: one row per user, created at
 * first comment through the §4 alias validation + idempotent profile
 * upsert (#66). The alias is ci-unique via
 * {@code uk_community_member_profiles_alias_ci} — the constraint name
 * #66's exception handler maps a different-user collision to 409
 * COMMUNITY_ALIAS_TAKEN.
 *
 * <p>NOT PII: a user-chosen pseudonym, never exposed as a directory.
 */
@Entity
@Table(name = "community_member_profiles")
public class CommunityMemberProfile {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private String alias;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityMemberProfile() {
    }

    public CommunityMemberProfile(Long userId, String alias) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.alias = Objects.requireNonNull(alias, "alias must not be null");
    }

    public Long getUserId() {
        return userId;
    }

    public String getAlias() {
        return alias;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
