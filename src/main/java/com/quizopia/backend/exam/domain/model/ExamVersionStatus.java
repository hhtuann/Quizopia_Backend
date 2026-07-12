package com.quizopia.backend.exam.domain.model;

/**
 * Lifecycle state of an exam version. {@code DRAFT} versions are editable;
 * {@code PUBLISHED} versions are immutable snapshots used for grading.
 */
public enum ExamVersionStatus {
    DRAFT,
    PUBLISHED
}
