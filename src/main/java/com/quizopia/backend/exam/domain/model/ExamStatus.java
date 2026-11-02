package com.quizopia.backend.exam.domain.model;

/**
 * Exam lifecycle (Task B redesign, issue #2): DRAFT → PUBLISHED → ARCHIVED.
 *
 * <ul>
 *   <li>DRAFT: composition fully editable.</li>
 *   <li>PUBLISHED: immutable assessment content and grading policy; one-way,
 *       no next content version, no return to DRAFT.</li>
 *   <li>ARCHIVED: no new sessions; historical sessions/attempts/results remain.</li>
 * </ul>
 */
public enum ExamStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
