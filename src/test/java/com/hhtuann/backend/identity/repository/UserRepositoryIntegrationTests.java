package com.hhtuann.backend.identity.repository;

import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link UserRepository} running against a real
 * PostgreSQL instance via Testcontainers (see
 * {@link PostgresTestContainerConfiguration}).
 *
 * <p>Each test is wrapped in a transaction that is rolled back at the end of
 * the test, so no persisted state leaks between tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class UserRepositoryIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Test
    void findByUsernameIgnoreCaseAndFindByEmailIgnoreCaseMatchRegardlessOfCasing() {
        User saved = userRepository.saveAndFlush(
                new User(
                        "CaseSensitiveUser",
                        "CaseSensitiveUser@Example.com",
                        "test-fake-password-hash",
                        "Case Sensitive User"
                )
        );

        Optional<User> byUsername = userRepository.findByUsernameIgnoreCase("casesensitiveuser");
        Optional<User> byEmail = userRepository.findByEmailIgnoreCase("CASESENSITIVEUSER@EXAMPLE.COM");

        assertThat(byUsername).isPresent();
        assertThat(byUsername.get().getId()).isEqualTo(saved.getId());

        assertThat(byEmail).isPresent();
        assertThat(byEmail.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void existsByUsernameIgnoreCaseAndExistsByEmailIgnoreCaseDetectExistingRegardlessOfCasing() {
        userRepository.saveAndFlush(
                new User(
                        "ExistingUser",
                        "ExistingUser@Example.com",
                        "test-fake-password-hash",
                        "Existing User"
                )
        );

        assertThat(userRepository.existsByUsernameIgnoreCase("existinguser")).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase("EXISTINGUSER@EXAMPLE.COM")).isTrue();

        assertThat(userRepository.existsByUsernameIgnoreCase("does-not-exist")).isFalse();
        assertThat(userRepository.existsByEmailIgnoreCase("does-not-exist@example.com")).isFalse();
    }
}
