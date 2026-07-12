package com.quizopia.backend.academic.api;

import com.quizopia.backend.academic.application.StudentOnboardingService;
import com.quizopia.backend.academic.dto.AssignSchoolRequest;
import com.quizopia.backend.academic.dto.PendingStudentItem;
import com.quizopia.backend.academic.dto.StudentProfileResponse;
import com.quizopia.backend.academic.dto.StudentSearchResponse;
import com.quizopia.backend.question.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Student onboarding endpoints. No class-level @RequestMapping — full paths per
 * method (consistent with UserController).
 *
 * <ul>
 *   <li>GET  /api/admin/pending-students — ACADEMIC_ADMIN lists unassigned students.</li>
 *   <li>POST /api/admin/students/{userId}/assign-school — assign to school + auto code.</li>
 *   <li>GET  /api/students/search — TEACHER searches students in their school.</li>
 * </ul>
 */
@RestController
public class StudentOnboardingController {

    private final StudentOnboardingService onboardingService;

    public StudentOnboardingController(StudentOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @GetMapping("/api/admin/pending-students")
    public PageResponse<PendingStudentItem> listPendingStudents(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return onboardingService.listPendingStudents(Long.valueOf(jwt.getSubject()), search, page, size);
    }

    @PostMapping("/api/admin/students/{userId}/assign-school")
    public ResponseEntity<StudentProfileResponse> assignStudentToSchool(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long userId,
            @Valid @RequestBody AssignSchoolRequest request) {
        StudentProfileResponse resp = onboardingService.assignStudentToSchool(
                Long.valueOf(jwt.getSubject()), userId, request.schoolId());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/api/students/search")
    public StudentSearchResponse searchStudents(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q) {
        return onboardingService.searchStudents(Long.valueOf(jwt.getSubject()), q);
    }
}
