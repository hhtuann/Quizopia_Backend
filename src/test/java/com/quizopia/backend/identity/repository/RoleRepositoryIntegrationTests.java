package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.Role;
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
 * Integration tests for {@link RoleRepository#findByCode(String)} running
 * against a real PostgreSQL instance via Testcontainers
 * (see {@link PostgresTestContainerConfiguration}).
 *
 * <p>Roles are seeded by Flyway migration {@code V3__seed_roles_and_permissions.sql};
 * no roles are created or persisted by these tests. Each test is wrapped in a
 * transaction that is rolled back, keeping the read-only seed data intact.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RoleRepositoryIntegrationTests {

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void findByCodeReturnsFlywaySeededRole() {
        Optional<Role> maybeSystemAdmin = roleRepository.findByCode("SYSTEM_ADMIN");

        assertThat(maybeSystemAdmin).isPresent();
        assertThat(maybeSystemAdmin.get().getId()).isNotNull();
        assertThat(maybeSystemAdmin.get().getCode()).isEqualTo("SYSTEM_ADMIN");
    }

    @Test
    void findByCodeIsCaseSensitiveAndReturnsEmptyForUnknownCode() {
        Optional<Role> lowerCased = roleRepository.findByCode("system_admin");
        assertThat(lowerCased).isEmpty();

        Optional<Role> unknown = roleRepository.findByCode("DOES_NOT_EXIST");
        assertThat(unknown).isEmpty();
    }
}
