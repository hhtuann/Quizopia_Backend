package com.quizopia.backend.classroom.api;

import com.quizopia.backend.classroom.application.StudentSearchService;
import com.quizopia.backend.classroom.dto.StudentSearchResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner search for classroom composition ({@code GET /api/students/search}).
 * TEACHER-gated, user-based (see {@link StudentSearchService}). The legacy
 * school onboarding endpoints that shared this prefix were decommissioned
 * with the organization architecture.
 */
@RestController
public class StudentSearchController {

    private final StudentSearchService studentSearchService;

    public StudentSearchController(StudentSearchService studentSearchService) {
        this.studentSearchService = studentSearchService;
    }

    @GetMapping("/api/students/search")
    public StudentSearchResponse searchStudents(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q) {
        return studentSearchService.searchStudents(Long.valueOf(jwt.getSubject()), q);
    }
}
