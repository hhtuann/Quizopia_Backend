package com.quizopia.backend.authentication;

import com.quizopia.backend.academic.application.DemoDataSeeder;
import com.quizopia.backend.authentication.application.RegistrationService;
import com.quizopia.backend.authentication.dto.AccountType;
import com.quizopia.backend.authentication.dto.RegisterRequest;
import com.quizopia.backend.authentication.dto.RegisterResponse;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.classroom.repository.ClassroomMemberRepository;
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
 * Verifies that registration (even with demo flag ON) does NOT grant anything
 * beyond the STUDENT role, and that the demo seeder provisions the demo
 * teacher, the three demo students, and the DEMO-CLASS classroom with its
 * roster.
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
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private ClassroomMemberRepository classroomMemberRepository;

    @Test
    void demoUsersAndClassroomSeeded() {
        var teacher = userRepository.findByUsernameIgnoreCase("demo-teacher").orElseThrow();
        assertThat(userRoleRepository.findActiveRoleCodesByUserId(teacher.getId(), Instant.now()))
                .containsExactly("TEACHER");
        var classroom = classroomRepository
                .findByOwnerUserIdAndCodeIgnoreCase(teacher.getId(), DemoDataSeeder.DEMO_CLASSROOM_CODE)
                .orElseThrow();
        for (int i = 1; i <= 3; i++) {
            var student = userRepository.findByUsernameIgnoreCase("demo-student-" + i).orElseThrow();
            assertThat(userRoleRepository.findActiveRoleCodesByUserId(student.getId(), Instant.now()))
                    .containsExactly("STUDENT");
            assertThat(classroomMemberRepository.existsByClassroomIdAndUserId(
                    classroom.getId(), student.getId())).isTrue();
        }
    }

    @Test
    void teacherSelfSelectionIsRejected() {
        // Identity-M1: TEACHER cannot be self-selected at registration.
        assertThatThrownBy(() -> registrationService.register(request(
                "v11-teacher-1", "v11t1@test.com", AccountType.TEACHER), null))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void studentRegistrationGrantsOnlyStudentRole() {
        RegisterResponse resp = registrationService.register(request(
                "v11-student-1", "v11s1@test.com", AccountType.STUDENT), null);
        assertThat(resp.id()).isNotNull();
        assertThat(userRoleRepository.findActiveRoleCodesByUserId(resp.id(), Instant.now()))
                .containsExactly("STUDENT");
    }

    private static RegisterRequest request(String username, String email, AccountType type) {
        return new RegisterRequest(
                username, email, "password123", "Display", "0123456789",
                type);
    }
}
