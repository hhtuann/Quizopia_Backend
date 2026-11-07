package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.Role;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserRole;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for
 * {@link UserRoleRepository#findActiveRoleCodesByUserId(Long, Instant)}
 * running against a real PostgreSQL instance via Testcontainers
 * (see {@link PostgresTestContainerConfiguration}).
 * <p>Roles used here are the two foundational roles seeded by Flyway
 * migration {@code V3__seed_roles_and_permissions.sql}; no roles are created.
 * Each test is wrapped in a transaction that is rolled back, so no assignment
 * state leaks between tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class UserRoleRepositoryIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Test
    void findActiveRoleCodesAppliesExpiryRules() {
        User user = userRepository.saveAndFlush(
                new User(
                        "UserRoleTestUser",
                        "user-role-test@example.com",
                        "test-fake-password-hash",
                        "User Role Test User"
                )
        );

        Role teacher = roleRepository.findByCode("TEACHER").orElseThrow();
        Role student = roleRepository.findByCode("STUDENT").orElseThrow();

        assertThat(teacher).isNotNull();
        assertThat(student).isNotNull();

        // evaluationNow is one day in the future so that every non-null
        // expiresAt used below is still greater than assignedAt at insert
        // time (no constraint violation), while STUDENT/TEACHER can still be
        // filtered out when evaluated against evaluationNow.
        Instant evaluationNow = Instant.now().plusSeconds(86400);

        userRoleRepository.saveAllAndFlush(List.of(
                new UserRole(user, teacher, null, null),
                new UserRole(user, student, null, evaluationNow.minusSeconds(3600))
        ));

        List<String> activeCodes =
                userRoleRepository.findActiveRoleCodesByUserId(user.getId(), evaluationNow);

        assertThat(activeCodes).containsExactly("TEACHER");
    }

    @Test
    void findActiveRoleCodesReturnsEmptyForUserWithoutRoles() {
        User user = userRepository.saveAndFlush(
                new User(
                        "UserWithoutRole",
                        "user-without-role@example.com",
                        "test-fake-password-hash",
                        "User Without Role"
                )
        );

        List<String> activeCodes =
                userRoleRepository.findActiveRoleCodesByUserId(user.getId(), Instant.now());

        assertThat(activeCodes).isEmpty();
    }
}
