package com.hhtuann.backend.exam.domain.model;

/**
 * Lifecycle state of an exam session (a sitting). The frozen state machine:
 * DRAFT → SCHEDULED → OPEN → CLOSED; DRAFT/SCHEDULED → CANCELLED.
 * No cancel from OPEN, no reopen of CLOSED.
 */
public enum ExamSessionStatus {
    DRAFT,
    SCHEDULED,
    OPEN,
    CLOSED,
    CANCELLED
}
