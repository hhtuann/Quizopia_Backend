package com.hhtuann.backend.attempt.domain.model;

/**
 * Idempotency operation kind (V9 {@code idempotency_records.operation}).
 *
 * <p>Pinned to {@link #ATTEMPT_SUBMIT} for the MVP by the DB CHECK
 * {@code chk_idempotency_operation}; the table is used solely to cache the
 * submit response for safe retry.
 */
public enum IdempotencyOperation {
    ATTEMPT_SUBMIT
}
