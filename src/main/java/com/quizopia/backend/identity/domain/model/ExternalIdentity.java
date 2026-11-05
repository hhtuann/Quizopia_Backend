package com.quizopia.backend.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * JPA entity mapping to {@code external_identities}. The stable business key
 * is (provider, issuer, subject) — never the email address (ADR-002 §3).
 */
@Entity
@Table(name = "external_identities", indexes = {
        @Index(name = "idx_external_identities_user", columnList = "user_id")
})
public class ExternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 50)
    private ExternalProvider provider;

    @Column(name = "issuer", nullable = false, length = 255)
    private String issuer;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    /** Email as reported at link time; audit only, not part of the key. */
    @Column(name = "email_at_link", length = 254)
    private String emailAtLink;

    @Column(name = "email_verified_at_link", nullable = false)
    private boolean emailVerifiedAtLink = false;

    @CreationTimestamp
    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected ExternalIdentity() {
    }

    public ExternalIdentity(User user, ExternalProvider provider, String issuer,
            String subject, String emailAtLink, boolean emailVerifiedAtLink) {
        this.user = user;
        this.provider = provider;
        this.issuer = issuer;
        this.subject = subject;
        this.emailAtLink = emailAtLink;
        this.emailVerifiedAtLink = emailVerifiedAtLink;
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public ExternalProvider getProvider() {
        return provider;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmailAtLink() {
        return emailAtLink;
    }

    public boolean isEmailVerifiedAtLink() {
        return emailVerifiedAtLink;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    @Override
    public String toString() {
        return "ExternalIdentity{id=" + id
                + ", userId=" + (user != null ? user.getId() : null)
                + ", provider=" + provider
                + ", issuer='" + issuer + '\''
                + ", subject='" + subject + '\''
                + ", linkedAt=" + linkedAt
                + ", lastLoginAt=" + lastLoginAt + '}';
    }
}
