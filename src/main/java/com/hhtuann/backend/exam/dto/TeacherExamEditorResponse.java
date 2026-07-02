package com.hhtuann.backend.exam.dto;

import com.hhtuann.backend.question.dto.SubjectSummary;
import java.time.Instant;
import java.util.List;

public record TeacherExamEditorResponse(
        Long id, String code, String title, String description,
        SubjectSummary subject, ExamPurposeSummary purpose,
        String status, Integer currentVersionNumber,
        ExamDraftVersionResponse currentDraftVersion,
        List<PublishedExamVersionSummary> publishedVersions,
        Instant createdAt, Instant updatedAt
) {}
