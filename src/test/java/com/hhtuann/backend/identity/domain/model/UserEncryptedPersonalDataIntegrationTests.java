package com.hhtuann.backend.identity.domain.model;

import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the V5 migration that adds encrypted personal-data
 * columns, running against a real PostgreSQL instance via Testcontainers.
 *
 * <p>The context load itself proves Hibernate {@code ddl-auto=validate} accepts
 * the new {@link User} mapping against the V5 schema. These tests then verify
 * Flyway reached version 5, that ciphertext can be persisted and read back, and
 * that no plaintext {@code phone}/{@code national_id} columns exist.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class UserEncryptedPersonalDataIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void flywayReachedVersionFive() {
        @SuppressWarnings("unchecked")
        List<String> versions = entityManager
                .createNativeQuery("SELECT version FROM flyway_schema_history WHERE type = 'SQL' ORDER BY installed_rank", String.class)
                .getResultList();

        // Latest version bumped to 12 by V12__simplify_numeric_answer_key.sql (NUMERIC answer_key).
        // This test pins the current Flyway version and must be updated whenever a migration is added.
        assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        assertThat(versions).endsWith("12");
    }

    @Test
    void persistsAndReadsBackEncryptedPersonalData() {
        User user = new User(
                "EncryptedDataUser",
                "encrypted-data-user@example.com",
                "argon2id-fake-hash",
                "Encrypted Data User");
        user.setPhoneEncrypted("v1:YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnh5eg");
        user.setNationalIdEncrypted("v1:MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0");
        User saved = userRepository.saveAndFlush(user);
        entityManager.clear();

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getPhoneEncrypted()).isEqualTo("v1:YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnh5eg");
        assertThat(reloaded.getNationalIdEncrypted()).isEqualTo("v1:MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0");
    }

    @Test
    void encryptedColumnsAreNullableSoExistingStyleRowsStillWork() {
        User user = new User(
                "NullEncryptedUser",
                "null-encrypted-user@example.com",
                "argon2id-fake-hash",
                "Null Encrypted User");
        User saved = userRepository.saveAndFlush(user);
        entityManager.clear();

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getPhoneEncrypted()).isNull();
        assertThat(reloaded.getNationalIdEncrypted()).isNull();
    }

    @Test
    void noPlaintextPhoneOrNationalIdColumnsExist() {
        @SuppressWarnings("unchecked")
        List<String> columns = entityManager
                .createNativeQuery(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_name = 'users' "
                                + "AND column_name IN ('phone', 'national_id', 'phone_encrypted', 'national_id_encrypted')",
                        String.class)
                .getResultList();

        assertThat(columns)
                .containsExactlyInAnyOrder("phone_encrypted", "national_id_encrypted");
    }
}
