package com.quizopia.backend.exam.dto;

import com.quizopia.backend.question.dto.SubjectSummary;
import java.time.Instant;

public record ExamListItem(
        Long id, String code, String title,
        SubjectSummary subject, ExamPurposeSummary purpose,
        String status, Integer currentVersionNumber,
        boolean hasDraft, boolean hasPublished,
        Instant createdAt
) {}
