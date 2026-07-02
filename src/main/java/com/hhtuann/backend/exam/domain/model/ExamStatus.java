package com.hhtuann.backend.exam.domain.model;

/**
 * Identity-level lifecycle state of an exam. {@code DRAFT} means no version has
 * been published yet; {@code READY} means at least one version is PUBLISHED.
 * The transition DRAFT → READY is one-way (never reverts).
 */
public enum ExamStatus {
    DRAFT,
    READY
}
