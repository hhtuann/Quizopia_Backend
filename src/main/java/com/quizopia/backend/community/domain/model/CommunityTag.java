package com.quizopia.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * JPA entity mapping {@code community_tags} (ADR-006 §3): the on-demand
 * label pool. Labels are always stored lower-cased and trimmed
 * ({@code [a-z0-9][a-z0-9 -]{0,39}}, unique case-insensitively). Rows are
 * created only via the {@code INSERT … ON CONFLICT DO NOTHING} upsert in
 * {@code CommunityTagRepository} — a caught constraint violation would
 * abort the enclosing PostgreSQL transaction.
 */
@Entity
@Table(name = "community_tags")
public class CommunityTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "label", nullable = false, length = 40, updatable = false)
    private String label;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunityTag() {
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }
}
