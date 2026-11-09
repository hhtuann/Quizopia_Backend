package com.quizopia.backend.classroom.api;

import com.quizopia.backend.classroom.application.ClassroomGradebookExportService;
import com.quizopia.backend.classroom.application.ClassroomGradebookService;
import com.quizopia.backend.question.dto.PageResponse;
import com.quizopia.backend.classroom.dto.reporting.ClassroomAnalyticsResponse;
import com.quizopia.backend.classroom.dto.reporting.GradebookResponse;
import com.quizopia.backend.classroom.dto.reporting.GradebookSessionColumn;
import com.quizopia.backend.classroom.dto.reporting.LearnerMyResultsItem;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CLASS-M3 (issue #54): class-scoped gradebook, analytics and XLSX export
 * (teacher/owner) + learner my-results. Authorization is server-side in the
 * services (ownership for teacher routes, current membership with uniform
 * 404 for the learner route) — the JWT subject is only the caller identity.
 */
@RestController
public class ClassroomReportingController {

    private final ClassroomGradebookService gradebookService;
    private final ClassroomGradebookExportService exportService;

    public ClassroomReportingController(ClassroomGradebookService gradebookService,
            ClassroomGradebookExportService exportService) {
        this.gradebookService = gradebookService;
        this.exportService = exportService;
    }

    /** Session columns with class-cohort summaries (paged, searchable). */
    @GetMapping("/api/classrooms/{classId}/gradebook/sessions")
    public PageResponse<GradebookSessionColumn> gradebookSessions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("classId") Long classId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "search", required = false) String search) {
        Long userId = Long.valueOf(jwt.getSubject());
        return gradebookService.listGradebookSessions(userId, classId, page, size, status, search);
    }

    /** The student × session matrix (bounded columns, paged rows). */
    @GetMapping("/api/classrooms/{classId}/gradebook")
    public GradebookResponse gradebook(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("classId") Long classId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sessionIds", required = false) List<Long> sessionIds,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "sort", defaultValue = "displayName") String sort,
            @RequestParam(name = "direction", defaultValue = "asc") String direction) {
        Long userId = Long.valueOf(jwt.getSubject());
        return gradebookService.gradebook(userId, classId, sessionIds, page, size, search,
                sort, direction);
    }

    /** Class analytics: overview + trend, or single-session detail. */
    @GetMapping("/api/classrooms/{classId}/analytics")
    public ClassroomAnalyticsResponse analytics(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("classId") Long classId,
            @RequestParam(name = "sessionId", required = false) Long sessionId) {
        Long userId = Long.valueOf(jwt.getSubject());
        return gradebookService.analytics(userId, classId, sessionId);
    }

    /** Owner-only XLSX export with the SAME filters/semantics as the matrix. */
    @GetMapping("/api/classrooms/{classId}/gradebook/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("classId") Long classId,
            @RequestParam(name = "sessionIds", required = false) List<Long> sessionIds,
            @RequestParam(name = "search", required = false) String search) {
        Long userId = Long.valueOf(jwt.getSubject());
        ClassroomGradebookExportService.ExportFile file =
                exportService.export(userId, classId, sessionIds, search);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename() + "\"")
                .contentType(MediaType.parseMediaType(ClassroomGradebookExportService.contentType()))
                .body(file.content());
    }

    /** Learner's own assigned-session outcomes (member-only, self-scoped). */
    @GetMapping("/api/classrooms/joined/{classId}/my-results")
    public PageResponse<LearnerMyResultsItem> myResults(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("classId") Long classId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        Long userId = Long.valueOf(jwt.getSubject());
        return gradebookService.myResults(userId, classId, page, size);
    }
}
