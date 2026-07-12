package com.quizopia.backend.academic.api;

import com.quizopia.backend.academic.application.AcademicService;
import com.quizopia.backend.academic.dto.CreateSchoolRequest;
import com.quizopia.backend.academic.dto.CreateSubjectRequest;
import com.quizopia.backend.academic.dto.GradeLevelListResponse;
import com.quizopia.backend.academic.dto.SchoolListResponse;
import com.quizopia.backend.academic.dto.SchoolResponse;
import com.quizopia.backend.academic.dto.SubjectListResponse;
import com.quizopia.backend.academic.dto.SubjectResponse;
import com.quizopia.backend.academic.dto.UpdateSchoolRequest;
import com.quizopia.backend.academic.dto.UpdateSubjectRequest;
import com.quizopia.backend.academic.dto.UpdateSubjectStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Academic REST endpoints. No class-level {@code @RequestMapping}: the full path
 * lives on the method (consistent with the {@code /api} prefix used across auth /
 * question / exam — never {@code /api/v1}).
 *
 * <ul>
 *   <li>{@code GET  /api/subjects} — school-scoped subject list (SUBJECT_READ).</li>
 *   <li>{@code POST /api/subjects} — create subject (SUBJECT_CREATE, ACADEMIC_ADMIN).</li>
 *   <li>{@code PUT  /api/subjects/{id}} — update name/description (SUBJECT_UPDATE).</li>
 *   <li>{@code PUT  /api/subjects/{id}/status} — change status (SUBJECT_STATUS_UPDATE).</li>
 *   <li>{@code GET  /api/grade-levels} — school-scoped grade-level list (GRADE_LEVEL_READ).</li>
 * </ul>
 * Fine-grained authorization (permission, school scope) is enforced in
 * {@link AcademicService}.
 */
@RestController
public class AcademicController {

    private final AcademicService academicService;

    public AcademicController(AcademicService academicService) {
        this.academicService = academicService;
    }

    /**
     * List ACTIVE subjects in the caller's school scope. TEACHER is scoped to
     * their profile's school; ACADEMIC_ADMIN may pass {@code schoolId} or, when
     * omitted, see all schools.
     */
    @GetMapping("/api/subjects")
    public SubjectListResponse listSubjects(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long gradeLevelId) {
        Long userId = Long.valueOf(jwt.getSubject());
        return academicService.listSubjectsForCaller(userId, schoolId, search, gradeLevelId);
    }

    @PostMapping("/api/subjects")
    public ResponseEntity<SubjectResponse> createSubject(
            @Valid @RequestBody CreateSubjectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        SubjectResponse response = academicService.createSubject(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/api/subjects/{id}")
    public SubjectResponse updateSubject(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSubjectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return academicService.updateSubject(userId, id, request);
    }

    @PutMapping("/api/subjects/{id}/status")
    public SubjectResponse updateSubjectStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSubjectStatusRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return academicService.updateSubjectStatus(userId, id, request);
    }

    /**
     * List grade levels in the caller's school scope (see {@code listSubjects}).
     * Used by subject-creation dropdowns.
     */
    @GetMapping("/api/grade-levels")
    public GradeLevelListResponse listGradeLevels(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long schoolId) {
        Long userId = Long.valueOf(jwt.getSubject());
        return academicService.listGradeLevelsForCaller(userId, schoolId);
    }

    // ============================================================
    // /api/schools — School CRUD
    // ============================================================

    @GetMapping("/api/schools")
    public SchoolListResponse listSchools(@AuthenticationPrincipal Jwt jwt) {
        return academicService.listSchools(Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/api/schools")
    public ResponseEntity<SchoolResponse> createSchool(
            @Valid @RequestBody CreateSchoolRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(academicService.createSchool(Long.valueOf(jwt.getSubject()), request));
    }

    @PutMapping("/api/schools/{id}")
    public SchoolResponse updateSchool(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSchoolRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return academicService.updateSchool(Long.valueOf(jwt.getSubject()), id, request);
    }
}
