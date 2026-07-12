package com.quizopia.backend.exam.api;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.dto.CreateExamRequest;
import com.quizopia.backend.exam.dto.CreateExamVersionRequest;
import com.quizopia.backend.exam.dto.CreateExamVersionResponse;
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
        ExamListItem response = examService.createExam(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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

    @PutMapping("/{examId}/draft/composition")
    public TeacherExamEditorResponse updateDraftComposition(
            @PathVariable Long examId,
            @Valid @RequestBody UpdateDraftCompositionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return examService.updateDraftComposition(userId, examId, request);
    }

    @PostMapping("/{examId}/versions")
    public ResponseEntity<CreateExamVersionResponse> createNextVersion(
            @PathVariable Long examId,
            @Valid @RequestBody(required = false) CreateExamVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        CreateExamVersionResponse response = examService.createNextVersion(userId, examId,
                request != null ? request : new CreateExamVersionRequest(null));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{examId}/publish")
    public PublishedExamSummary publishExam(
            @PathVariable Long examId,
            @Valid @RequestBody(required = false) PublishExamRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return examService.publishExam(userId, examId,
                request != null ? request : new PublishExamRequest(null));
    }
}
