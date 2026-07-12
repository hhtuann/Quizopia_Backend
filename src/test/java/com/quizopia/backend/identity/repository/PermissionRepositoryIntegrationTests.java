package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.Permission;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link PermissionRepository#findByCode(String)}
 * running against a real PostgreSQL instance via Testcontainers
 * (see {@link PostgresTestContainerConfiguration}).
 *
 * <p>Permissions are seeded by Flyway migration
 * {@code V3__seed_roles_and_permissions.sql}; no permissions are created or
 * persisted by these tests. Each test is wrapped in a transaction that is
 * rolled back, keeping the read-only seed data intact.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class PermissionRepositoryIntegrationTests {

    @Autowired
    private PermissionRepository permissionRepository;

    @Test
    void findByCodeReturnsFlywaySeededPermission() {
        Optional<Permission> maybeUserRead = permissionRepository.findByCode("USER_READ");

        assertThat(maybeUserRead).isPresent();
        assertThat(maybeUserRead.get().getId()).isNotNull();
        assertThat(maybeUserRead.get().getCode()).isEqualTo("USER_READ");
    }

    @Test
    void findByCodeIsCaseSensitiveAndReturnsEmptyForUnknownCode() {
        Optional<Permission> lowerCased = permissionRepository.findByCode("user_read");
        assertThat(lowerCased).isEmpty();

        Optional<Permission> unknown = permissionRepository.findByCode("DOES_NOT_EXIST");
        assertThat(unknown).isEmpty();
    }
}
