package com.quizopia.backend.exam.domain.model;

/**
 * Eligibility state of a participant in an exam session. {@code ELIGIBLE} means
 * the student may start an attempt; {@code BLOCKED} means the student is
 * disabled (e.g. for misconduct) but the row is retained for history.
 */
public enum ExamSessionParticipantStatus {
    ELIGIBLE,
    BLOCKED
}
