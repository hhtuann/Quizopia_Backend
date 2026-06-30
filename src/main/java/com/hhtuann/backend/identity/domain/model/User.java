package com.hhtuann.backend.identity.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * JPA Entity mapping to the {@code users} table.
 * Stores authentication information and account status.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    /**
     * AES-256-GCM ciphertext of the user's phone number.
     * <p>
     * <strong>Security:</strong> This is ciphertext, never plaintext. The
     * encryption key lives outside the database (environment variable). This
     * value must never be logged or exposed through the API. It is therefore
     * intentionally excluded from {@link #toString()}.
     */
    @Column(name = "phone_encrypted", columnDefinition = "text")
    private String phoneEncrypted;

    /**
     * AES-256-GCM ciphertext of the user's national identifier.
     * <p>
     * <strong>Security:</strong> This is ciphertext, never plaintext. The
     * encryption key lives outside the database (environment variable). This
     * value must never be logged or exposed through the API. It is therefore
     * intentionally excluded from {@link #toString()}.
     */
    @Column(name = "national_id_encrypted", columnDefinition = "text")
    private String nationalIdEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 0;

    @Column(name = "failed_login_attempts", nullable = false)
    private Integer failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Default constructor required by JPA.
     */
    protected User() {
    }

    /**
     * Constructor for creating a new user.
     *
     * @param username     the unique username
     * @param email        the unique email address
     * @param passwordHash the Argon2id hashed password
     * @param displayName  the display name
     */
    public User(String username, String email, String passwordHash, String displayName) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.passwordChangedAt = Instant.now();
    }

    // ============================================================
    // Getters and Setters
    // ============================================================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Returns the password hash.
     * <p>
     * <strong>Security:</strong> Never log or expose this value in API responses.
     *
     * @return the Argon2id hashed password
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the phone ciphertext.
     * <p>
     * <strong>Security:</strong> Never log or expose this value.
     *
     * @return the AES-256-GCM ciphertext of the phone number, or {@code null}
     */
    public String getPhoneEncrypted() {
        return phoneEncrypted;
    }

    public void setPhoneEncrypted(String phoneEncrypted) {
        this.phoneEncrypted = phoneEncrypted;
    }

    /**
     * Returns the national identifier ciphertext.
     * <p>
     * <strong>Security:</strong> Never log or expose this value.
     *
     * @return the AES-256-GCM ciphertext of the national identifier, or {@code null}
     */
    public String getNationalIdEncrypted() {
        return nationalIdEncrypted;
    }

    public void setNationalIdEncrypted(String nationalIdEncrypted) {
        this.nationalIdEncrypted = nationalIdEncrypted;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Integer getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(Integer tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    public Integer getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void setFailedLoginAttempts(Integer failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(Instant passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    // ============================================================
    // equals and hashCode (based on ID only)
    // ============================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof User other)) {
            return false;
        }

        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return User.class.hashCode();
    }

    // ============================================================
    // toString (excludes sensitive and association fields)
    // ============================================================

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", displayName='" + displayName + '\'' +
                ", status=" + status +
                ", tokenVersion=" + tokenVersion +
                ", failedLoginAttempts=" + failedLoginAttempts +
                ", lockedUntil=" + lockedUntil +
                ", lastLoginAt=" + lastLoginAt +
                ", passwordChangedAt=" + passwordChangedAt +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
