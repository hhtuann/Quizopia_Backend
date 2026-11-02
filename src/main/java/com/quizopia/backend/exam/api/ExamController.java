package com.quizopia.backend.exam.api;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.dto.CreateExamRequest;
import com.quizopia.backend.exam.dto.ExamListItem;
import com.quizopia.backend.exam.dto.PublishedExamSummary;
import com.quizopia.backend.exam.dto.PublishExamRequest;
import com.quizopia.backend.exam.dto.TeacherExamEditorResponse;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest;
import com.quizopia.backend.question.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Exam management API — one exam = one immutable published content set
 * (issue #2). {@code /api/exams/{examId}} is the management home; session
 * routes are nested under it (ExamSessionNestedController).
 */
@RestController
@RequestMapping("/api/exams")
public class ExamController {

    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    @PostMapping
    public ResponseEntity<ExamListItem> createExam(
            @Valid @RequestBody CreateExamRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(examService.createExam(userId, request));
    }

    @GetMapping("/my")
    public PageResponse<ExamListItem> myExams(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String status) {
        Long userId = Long.valueOf(jwt.getSubject());
        return examService.listMyExams(userId, search, subjectId, status, page, size, sort);
    }

    @GetMapping("/{examId}")
    public TeacherExamEditorResponse getExamDetail(
            @PathVariable Long examId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return examService.getExamDetail(userId, examId);
    }

    @PutMapping("/{examId}")
    public TeacherExamEditorResponse updateExamMetadata(
            @PathVariable Long examId,
            @AuthenticationPrincipal Jwt jwt) {
        // Metadata-only updates ride on the composition endpoint's settings;
        // this alias exists for route completeness.
        Long userId = Long.valueOf(jwt.getSubject());
        return examService.getExamDetail(userId, examId);
    }

    @PutMapping("/{examId}/composition")
    public TeacherExamEditorResponse updateComposition(
            @PathVariable Long examId,
            @Valid @RequestBody UpdateDraftCompositionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return examService.updateDraftComposition(userId, examId, request);
    }

    /** Legacy alias retained during the frontend transition. */
    @PutMapping("/{examId}/draft/composition")
    public TeacherExamEditorResponse updateDraftCompositionLegacy(
            @PathVariable Long examId,
            @Valid @RequestBody UpdateDraftCompositionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return examService.updateDraftComposition(userId, examId, request);
    }

    @PostMapping("/{examId}/publish")
    public PublishedExamSummary publishExam(
            @PathVariable Long examId,
            @Valid @RequestBody(required = false) PublishExamRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return examService.publishExam(userId, examId, request);
    }

    @PostMapping("/{examId}/duplicate")
    public ResponseEntity<ExamListItem> duplicateExam(
            @PathVariable Long examId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(examService.duplicateExam(userId, examId));
    }

    @PostMapping("/{examId}/archive")
    public TeacherExamEditorResponse archiveExam(
            @PathVariable Long examId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return examService.archiveExam(userId, examId);
    }
}
