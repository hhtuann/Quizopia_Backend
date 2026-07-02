package com.hhtuann.backend.exam.repository;

import com.hhtuann.backend.exam.domain.model.ExamVersionStatus;

/**
 * Lightweight projection of (examId, status) from {@code exam_versions}, used
 * to batch-load version flags for a page of exams without loading full entities
 * or issuing per-exam queries (fixes N+1 in listMyExams).
 */
public interface ExamVersionStatusProjection {
    Long getExamId();
    ExamVersionStatus getStatus();
}
