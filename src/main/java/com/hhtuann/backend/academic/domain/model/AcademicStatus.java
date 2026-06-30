package com.hhtuann.backend.academic.domain.model;

/**
 * Status shared by school-scoped academic entities (schools, subjects).
 * Persisted as VARCHAR via {@link jakarta.persistence.EnumType#STRING}.
 */
public enum AcademicStatus {
    ACTIVE,
    INACTIVE,
    ARCHIVED
}
