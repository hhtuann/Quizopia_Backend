package com.hhtuann.backend.exam.dto;

import com.hhtuann.backend.question.dto.SubjectSummary;
import java.time.Instant;

public record ExamListItem(
        Long id, String code, String title,
        SubjectSummary subject, ExamPurposeSummary purpose,
        String status, Integer currentVersionNumber,
        boolean hasDraft, boolean hasPublished,
        Instant createdAt
) {}
