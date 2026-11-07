package com.quizopia.backend.authentication;

import com.quizopia.backend.authentication.application.RegistrationService;
import com.quizopia.backend.authentication.dto.AccountType;
import com.quizopia.backend.authentication.dto.RegisterRequest;
import com.quizopia.backend.authentication.dto.RegisterResponse;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that in production mode ({@code quizopia.demo.data.enabled=false},
 * the default), registration creates {@code User + UserRole(STUDENT)} only.
 * Identity-M1: TEACHER cannot be self-selected at registration.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RegistrationProfileIntegrationTests {

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Test
    void teacherSelfSelectionWithoutDemoFlag_isRejected() {
        assertThatThrownBy(() -> registrationService.register(request(
                "prod-teacher-1", "pt1@test.com", AccountType.TEACHER), null))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void studentRegistrationWithoutDemoFlag_grantsOnlyStudentRole() {
        RegisterResponse resp = registrationService.register(request(
                "prod-student-1", "ps1@test.com", AccountType.STUDENT), null);
        assertThat(resp.id()).isNotNull();
        assertThat(userRepository.findById(resp.id())).isPresent();
        assertThat(userRoleRepository.findActiveRoleCodesByUserId(resp.id(), Instant.now()))
                .containsExactly("STUDENT");
    }

    private static RegisterRequest request(String username, String email, AccountType type) {
        return new RegisterRequest(
                username, email, "password123", "Display", "0123456789",
                type);
    }
}
