package com.quizopia.backend.exam.dto;

import com.quizopia.backend.question.dto.SubjectSummary;

import java.math.BigDecimal;
import java.time.Instant;

public record ExamListItem(
        Long id, String code, String title,
        SubjectSummary subject, ExamPurposeSummary purpose,
        String status,
        Long questionCount,
        BigDecimal totalPoints,
        Instant publishedAt,
        Instant createdAt
) {}
