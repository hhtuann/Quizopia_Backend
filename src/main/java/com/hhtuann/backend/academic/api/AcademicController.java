package com.hhtuann.backend.academic.api;

import com.hhtuann.backend.academic.application.AcademicService;
import com.hhtuann.backend.academic.dto.SubjectListResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Academic REST endpoints. Currently exposes the school-scoped subject list
 * ({@code GET /api/subjects}) used by the teacher question-bank / exam creation
 * flows.
 *
 * <p>No class-level {@code @RequestMapping}: the full path lives on the method
 * (consistent with the {@code /api} prefix used across auth Day 4 / question
 * Day 5 / exam Day 6 — never {@code /api/v1}).
 */
@RestController
public class AcademicController {

    private final AcademicService academicService;

    public AcademicController(AcademicService academicService) {
        this.academicService = academicService;
    }

    /**
     * List ACTIVE subjects in the caller's school. Requires SUBJECT_READ.
     *
     * @param search        optional case-insensitive name/code substring
     * @param gradeLevelId  optional grade-level filter
     */
    @GetMapping("/api/subjects")
    public SubjectListResponse listSubjects(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long gradeLevelId) {
        Long userId = Long.valueOf(jwt.getSubject());
        return academicService.listSubjectsForCaller(userId, search, gradeLevelId);
    }
}
