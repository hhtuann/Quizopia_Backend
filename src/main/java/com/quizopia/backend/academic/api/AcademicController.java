package com.quizopia.backend.academic.api;

import com.quizopia.backend.academic.application.AcademicService;
import com.quizopia.backend.academic.dto.GradeLevelListResponse;
import com.quizopia.backend.academic.dto.SubjectListResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Academic catalog REST endpoints (read-only since the user-centric
 * refactor). No class-level {@code @RequestMapping}: the full path lives
 * on the method (consistent with the {@code /api} prefix used across
 * auth / question / exam — never {@code /api/v1}).
 *
 * <ul>
 *   <li>{@code GET /api/subjects} — global ACTIVE subject list (SUBJECT_READ).</li>
 *   <li>{@code GET /api/grade-levels} — global grade-level list (GRADE_LEVEL_READ).</li>
 * </ul>
 * The catalog is seed-managed (V24); school/subject management endpoints
 * were decommissioned with the organization architecture.
 */
@RestController
public class AcademicController {

    private final AcademicService academicService;

    public AcademicController(AcademicService academicService) {
        this.academicService = academicService;
    }

    /** List ACTIVE subjects of the global catalog (optional search + grade filter). */
    @GetMapping("/api/subjects")
    public SubjectListResponse listSubjects(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long gradeLevelId) {
        return academicService.listSubjectsForCaller(Long.valueOf(jwt.getSubject()), search, gradeLevelId);
    }

    /** List grade levels of the global catalog. */
    @GetMapping("/api/grade-levels")
    public GradeLevelListResponse listGradeLevels(@AuthenticationPrincipal Jwt jwt) {
        return academicService.listGradeLevelsForCaller(Long.valueOf(jwt.getSubject()));
    }
}
