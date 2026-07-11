package com.hhtuann.backend.authentication;

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
 * Verifies that in production mode ({@code quizopia.demo.data.enabled=false},
 * the default), registration creates {@code User + UserRole} but NO academic
 * profile.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RegistrationProfileIntegrationTests {

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private TeacherProfileRepository teacherProfileRepository;

    @Autowired
    private StudentProfileRepository studentProfileRepository;

    @Test
    void teacherRegistrationWithoutDemoFlag_createsNoTeacherProfile() {
        RegisterResponse resp = registrationService.register(request(
                "prod-teacher-1", "pt1@test.com", AccountType.TEACHER));
        assertThat(teacherProfileRepository.existsByUserId(resp.id())).isFalse();
    }

    @Test
    void studentRegistrationWithoutDemoFlag_createsNoStudentProfile() {
        RegisterResponse resp = registrationService.register(request(
                "prod-student-1", "ps1@test.com", AccountType.STUDENT));
        assertThat(studentProfileRepository.existsByUserId(resp.id())).isFalse();
    }

    private static RegisterRequest request(String username, String email, AccountType type) {
        return new RegisterRequest(
                username, email, "password123", "Display", "0123456789",
                type,
                type == AccountType.TEACHER ? "test-teacher-invite-code-only" : null);
    }
}
