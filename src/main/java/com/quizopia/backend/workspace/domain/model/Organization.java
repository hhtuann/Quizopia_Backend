package com.quizopia.backend.workspace.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code organizations} table — the institutional
 * tenant of the public platform (ADR-001 §2.1). Legacy schools are migrated
 * one-to-one with {@code legacySchoolId} provenance; organizations created
 * after the migration have {@code legacySchoolId = null}.
 */
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 1:1 provenance from the school era; null for organizations created post-migration. */
    @Column(name = "legacy_school_id", unique = true)
    private Long legacySchoolId;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "Asia/Ho_Chi_Minh";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Organization() {
    }

    public Organization(String code, String name, Long legacySchoolId) {
        this.code = requireNonBlank("code", code);
        this.name = requireNonBlank("name", name);
        this.legacySchoolId = legacySchoolId;
    }

    public Long getId() {
        return id;
    }

    public Long getLegacySchoolId() {
        return legacySchoolId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getTimezone() {
        return timezone;
    }

    public OrganizationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Organization other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Organization.class.hashCode();
    }

    @Override
    public String toString() {
        return "Organization{" +
                "id=" + id +
                ", legacySchoolId=" + legacySchoolId +
                ", code='" + code + '\'' +
                ", name='" + name + '\'' +
                ", status=" + status +
                '}';
    }

    static String requireNonBlank(String fieldName, String value) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
