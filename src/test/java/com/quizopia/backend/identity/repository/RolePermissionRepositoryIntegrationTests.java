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
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for
 * {@link RolePermissionRepository#findEffectivePermissionCodesByUserId(Long, Instant)}
 * running against a real PostgreSQL instance via Testcontainers
 * (see {@link PostgresTestContainerConfiguration}).
 *
 * <p>The role-permission matrix is seeded by Flyway migration
 * {@code V3__seed_roles_and_permissions.sql}; no roles, permissions or grants
 * are created by these tests. Each test is wrapped in a transaction that is
 * rolled back, so no state leaks between tests.
 *
 * <p>Permission codes used below are taken verbatim from V3:
 * <ul>
 *   <li>{@code EXAM_READ} - granted to both TEACHER (3.3) and STUDENT (3.4),
 *       used to verify {@code distinct} collapses duplicates.</li>
 *   <li>{@code QUESTION_CREATE} - granted to TEACHER only (3.3).</li>
 *   <li>{@code ATTEMPT_START} - granted to STUDENT only (3.4).</li>
 *   <li>{@code USER_READ} - granted to ACADEMIC_ADMIN (3.2) but not to TEACHER
 *       or STUDENT; used to verify an expired role's permissions are excluded.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RolePermissionRepositoryIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Test
    void findEffectivePermissionCodesAppliesExpiryAndDistinct() {
        User user = userRepository.saveAndFlush(
                new User(
                        "EffectivePermissionUser",
                        "effective-permission-user@example.com",
                        "test-fake-password-hash",
                        "Effective Permission User"
                )
        );

        Role teacher = roleRepository.findByCode("TEACHER").orElseThrow();
        Role student = roleRepository.findByCode("STUDENT").orElseThrow();
        Role academicAdmin = roleRepository.findByCode("ACADEMIC_ADMIN").orElseThrow();

        // evaluationNow is one day in the future so that every non-null
        // expiresAt used below remains greater than assignedAt at insert time
        // (no constraint violation). ACADEMIC_ADMIN's expiresAt still falls
        // before evaluationNow, so it is treated as expired when queried.
        Instant evaluationNow = Instant.now().plusSeconds(86400);

        userRoleRepository.saveAllAndFlush(List.of(
                new UserRole(user, teacher, null, null),
                new UserRole(user, student, null, evaluationNow.plusSeconds(3600)),
                new UserRole(user, academicAdmin, null, evaluationNow.minusSeconds(3600))
        ));

        List<String> effectiveCodes =
                rolePermissionRepository.findEffectivePermissionCodesByUserId(user.getId(), evaluationNow);

        // EXAM_READ is granted to both active roles (TEACHER + STUDENT);
        // distinct must collapse the two grants into a single occurrence.
        assertThat(effectiveCodes).contains("EXAM_READ");
        assertThat(Collections.frequency(effectiveCodes, "EXAM_READ")).isEqualTo(1);

        // QUESTION_CREATE is granted to TEACHER only and TEACHER is active.
        assertThat(effectiveCodes).contains("QUESTION_CREATE");

        // ATTEMPT_START is granted to STUDENT only and STUDENT is active.
        assertThat(effectiveCodes).contains("ATTEMPT_START");

        // USER_READ is granted to ACADEMIC_ADMIN only among these roles, but
        // ACADEMIC_ADMIN is expired, so its permissions must be excluded.
        assertThat(effectiveCodes).doesNotContain("USER_READ");
    }

    @Test
    void findEffectivePermissionCodesReturnsEmptyForUserWithoutRoles() {
        User user = userRepository.saveAndFlush(
                new User(
                        "UserWithoutEffectivePermission",
                        "user-without-effective-permission@example.com",
                        "test-fake-password-hash",
                        "User Without Effective Permission"
                )
        );

        List<String> effectiveCodes =
                rolePermissionRepository.findEffectivePermissionCodesByUserId(user.getId(), Instant.now());

        assertThat(effectiveCodes).isEmpty();
    }
}
