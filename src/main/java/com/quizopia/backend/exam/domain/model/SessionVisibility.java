package com.quizopia.backend.exam.domain.model;

/**
 * Exam-session visibility (V10). Controls who can see/start an attempt:
 * <ul>
 *   <li>{@link #PUBLIC} — any student in the same school.</li>
 *   <li>{@link #CLASS_RESTRICTED} — only students that are members of at least
 *       one classroom assigned to the session via {@code exam_session_classes}.</li>
 * </ul>
 * Default is {@link #CLASS_RESTRICTED} (safe): a teacher must explicitly choose
 * PUBLIC. Stored as STRING in {@code exam_sessions.visibility}.
 */
public enum SessionVisibility {
    PUBLIC,
    CLASS_RESTRICTED
}
