package com.hhtuann.backend.authentication;

import com.hhtuann.backend.academic.application.DemoDataSeeder;
import com.hhtuann.backend.academic.repository.SchoolRepository;
import com.hhtuann.backend.academic.repository.StudentProfileRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.authentication.application.RegistrationService;
import com.hhtuann.backend.authentication.dto.AccountType;
import com.hhtuann.backend.authentication.dto.RegisterRequest;
import com.hhtuann.backend.authentication.dto.RegisterResponse;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that registration (even with demo flag ON) does NOT auto-create
 * academic profiles (V11 Student Onboarding). Students/teachers self-register →
 * PENDING (user + role, no profile). ACADEMIC_ADMIN assigns them to a school
 * via the StudentOnboardingService.
 *
 * <p>Uses a dedicated Spring context with the demo flag enabled so the
 * {@link DemoDataSeeder} runs at startup.
 */
@SpringBootTest(properties = "quizopia.demo.data.enabled=true")
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RegistrationDemoModeIntegrationTests {

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private TeacherProfileRepository teacherProfileRepository;

    @Autowired
    private StudentProfileRepository studentProfileRepository;

    @Autowired
    private SchoolRepository schoolRepository;

    @Test
    void demoSchoolSeeded() {
        assertThat(schoolRepository.findByCodeIgnoreCase(DemoDataSeeder.DEMO_SCHOOL_CODE))
                .isPresent();
    }

    @Test
    void teacherRegistrationDoesNotCreateProfile() {
        RegisterResponse resp = registrationService.register(request(
                "v11-teacher-1", "v11t1@test.com", AccountType.TEACHER));
        assertThat(resp.id()).isNotNull();
        // V11: registration creates ONLY user + role, NOT a profile.
        assertThat(teacherProfileRepository.existsByUserId(resp.id())).isFalse();
    }

    @Test
    void studentRegistrationDoesNotCreateProfile() {
        RegisterResponse resp = registrationService.register(request(
                "v11-student-1", "v11s1@test.com", AccountType.STUDENT));
        assertThat(resp.id()).isNotNull();
        // V11: registration creates ONLY user + role, NOT a profile.
        assertThat(studentProfileRepository.existsByUserId(resp.id())).isFalse();
    }

    private static RegisterRequest request(String username, String email, AccountType type) {
        return new RegisterRequest(
                username, email, "password123", "Display", "0123456789",
                type,
                type == AccountType.TEACHER ? "test-teacher-invite-code-only" : null);
    }
}
