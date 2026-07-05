package com.hhtuann.backend.attempt.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the V9 {@code idempotency_records} table — the cached
 * submit response used for safe retry.
 *
 * <p>The repository performs only data lookups. <b>Ownership is enforced by the
 * service (A3.2), not the repository or DB:</b> the service must verify that
 * (1) {@code userId} is the caller / owner of the attempt, and
 * (2) {@code idempotencyKey} matches {@code Attempt.submissionIdempotencyKey}.
 * No DB constraint links these across tables (see A2 review LOW-1/LOW-2);
 * adding one is explicitly out of scope here.
 *
 * <p>The DB pins {@code operation = ATTEMPT_SUBMIT}, {@code response_status = 200},
 * {@code response_body} to a JSON object, and {@code expires_at} to NULL (MVP
 * no-expiry). {@code request_hash} does not exist in this table.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 80)
    private IdempotencyOperation operation;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", nullable = false, columnDefinition = "jsonb")
    private JsonNode responseBody;

    /** Always NULL in the MVP (CHECK chk_idempotency_no_expiry). */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(Long userId, Long attemptId, IdempotencyOperation operation,
                             String idempotencyKey, int responseStatus, JsonNode responseBody) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        this.idempotencyKey = idempotencyKey;
        this.responseStatus = responseStatus;
        this.responseBody = Objects.requireNonNull(responseBody, "responseBody must not be null");
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public IdempotencyOperation getOperation() {
        return operation;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public JsonNode getResponseBody() {
        return responseBody;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IdempotencyRecord other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return IdempotencyRecord.class.hashCode();
    }

    @Override
    public String toString() {
        return "IdempotencyRecord{"
                + "id=" + id
                + ", userId=" + userId
                + ", attemptId=" + attemptId
                + ", operation=" + operation
                + ", idempotencyKey='" + idempotencyKey + '\''
                + ", responseStatus=" + responseStatus
                + '}';
    }
}
