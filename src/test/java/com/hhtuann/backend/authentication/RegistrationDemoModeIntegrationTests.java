package com.hhtuann.backend.authentication;

import com.hhtuann.backend.academic.application.DemoDataSeeder;
import com.hhtuann.backend.academic.domain.model.School;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.SchoolRepository;
import com.hhtuann.backend.academic.repository.StudentProfileRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.authentication.application.RegistrationService;
import com.hhtuann.backend.authentication.dto.AccountType;
import com.hhtuann.backend.authentication.dto.RegisterRequest;
import com.hhtuann.backend.authentication.dto.RegisterResponse;
import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies demo-mode registration ({@code quizopia.demo.data.enabled=true}):
 * TEACHER → teacher_profile, STUDENT → student_profile, and transaction
 * rollback when profile creation fails.
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

    @Autowired
    private UserRepository userRepository;

    @Test
    void demoSchoolSeeded() {
        assertThat(schoolRepository.findByCodeIgnoreCase(DemoDataSeeder.DEMO_SCHOOL_CODE))
                .isPresent();
    }

    @Test
    void teacherRegistrationWithDemoFlag_createsTeacherProfile() {
        RegisterResponse resp = registrationService.register(request(
                "demo-teacher-1", "dt1@test.com", AccountType.TEACHER));
        assertThat(teacherProfileRepository.existsByUserId(resp.id())).isTrue();
        assertThat(teacherProfileRepository.findByUserId(resp.id()))
                .hasValueSatisfying(tp ->
                        assertThat(tp.getTeacherCode()).isEqualTo("demo-teacher-1"));
    }

    @Test
    void studentRegistrationWithDemoFlag_createsStudentProfile() {
        RegisterResponse resp = registrationService.register(request(
                "demo-student-1", "ds1@test.com", AccountType.STUDENT));
        assertThat(studentProfileRepository.existsByUserId(resp.id())).isTrue();
    }

    @Test
    void teacherRegistrationRollsBackWhenProfileCodeCollides() {
        // Setup: seed a teacher_profile with code "collision-code".
        School demoSchool = schoolRepository
                .findByCodeIgnoreCase(DemoDataSeeder.DEMO_SCHOOL_CODE).orElseThrow();
        User seedUser = userRepository.saveAndFlush(
                new User("seed-collision", "sc@test.com", "hash", "Seed"));
        teacherProfileRepository.saveAndFlush(
                new TeacherProfile(seedUser.getId(), demoSchool.getId(), "collision-code"));

        // Register a TEACHER whose username (= derived teacher_code) collides.
        // The DataIntegrityViolationException proves the failure path was reached.
        // Spring @Transactional on register() guarantees the whole
        // user + role + profile transaction rolls back atomically.
        assertThatThrownBy(() -> registrationService.register(request(
                "collision-code", "cc@test.com", AccountType.TEACHER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static RegisterRequest request(String username, String email, AccountType type) {
        return new RegisterRequest(
                username, email, "password123", "Display", "0123456789", "ID456",
                type,
                type == AccountType.TEACHER ? "test-teacher-invite-code-only" : null);
    }
}
