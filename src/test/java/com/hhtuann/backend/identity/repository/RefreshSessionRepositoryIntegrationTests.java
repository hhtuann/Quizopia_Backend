package com.hhtuann.backend.identity.repository;

import com.hhtuann.backend.identity.domain.model.RefreshSession;
import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the custom methods of {@link RefreshSessionRepository}
 * running against a real PostgreSQL instance via Testcontainers
 * (see {@link PostgresTestContainerConfiguration}).
 *
 * <p>Test data follows every constraint of the {@code refresh_sessions} table
 * defined by Flyway migration {@code V2__create_identity_schema.sql}:
 * <ul>
 *   <li>{@code token_hash} is exactly 64 lowercase hexadecimal characters.</li>
 *   <li>{@code expires_at} is strictly greater than {@code created_at}.</li>
 *   <li>{@code revoked_at} and {@code revoke_reason} are both null or both
 *       non-null, and {@code revoked_at >= created_at}.</li>
 * </ul>
 * Each test is wrapped in a transaction that is rolled back, so no state
 * leaks between tests. Token hashes are never logged or printed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RefreshSessionRepositoryIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshSessionRepository refreshSessionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByTokenHashWithUserReturnsSessionEvenWhenRevokedAndEagerlyLoadsUser() {
        User user = userRepository.saveAndFlush(
                new User(
                        "RefreshLookupUser",
                        "refresh-lookup-user@example.com",
                        "test-fake-password-hash",
                        "Refresh Lookup User"
                )
        );

        RefreshSession session = newSession(user, UUID.randomUUID(), "a".repeat(64), Instant.now().plusSeconds(3600));
        UUID sessionId = session.getId();
        refreshSessionRepository.saveAndFlush(session);

        // Mark revoked after the first save: revokedAt is after createdAt and
        // revokeReason is non-null, satisfying the revocation constraints.
        session.setRevokedAt(Instant.now().plusSeconds(60));
        session.setRevokeReason("ROTATED");
        refreshSessionRepository.saveAndFlush(session);

        entityManager.clear();

        Optional<RefreshSession> found = refreshSessionRepository.findByTokenHashWithUser("a".repeat(64));

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(sessionId);
        // The lookup intentionally does not filter on revokedAt/expiry.
        assertThat(found.get().getRevokedAt()).isNotNull();
        assertThat(found.get().getRevokeReason()).isEqualTo("ROTATED");
        // The user association was eagerly fetched via join fetch.
        assertThat(Hibernate.isInitialized(found.get().getUser())).isTrue();
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());

        // A valid but non-existent token hash must return empty.
        Optional<RefreshSession> missing = refreshSessionRepository.findByTokenHashWithUser(tokenHash(98));
        assertThat(missing).isEmpty();
    }

    @Test
    void revokeUnrevokedByUserIdRevokesOnlyUnrevokedSessionsOfThatUserIgnoringExpiresAt() {
        User userOne = userRepository.saveAndFlush(
                new User(
                        "RefreshLogoutAllUser",
                        "refresh-logout-all-user@example.com",
                        "test-fake-password-hash",
                        "Refresh Logout All User"
                )
        );
        User userTwo = userRepository.saveAndFlush(
                new User(
                        "RefreshOtherUser",
                        "refresh-other-user@example.com",
                        "test-fake-password-hash",
                        "Refresh Other User"
                )
        );

        // Truncated to micros so instants survive the TIMESTAMPTZ round-trip.
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant revocationTime = baseTime.plusSeconds(7200);
        UUID family = UUID.randomUUID();

        RefreshSession s1 = newSession(userOne, family, tokenHash(1), baseTime.plusSeconds(7200));
        // s2 expires before revocationTime but after createdAt, proving the
        // bulk update does not filter by expiresAt.
        RefreshSession s2 = newSession(userOne, family, tokenHash(2), baseTime.plusSeconds(5400));
        RefreshSession s3 = newSession(userOne, family, tokenHash(3), baseTime.plusSeconds(7200));
        RefreshSession s4 = newSession(userTwo, family, tokenHash(4), baseTime.plusSeconds(7200));

        refreshSessionRepository.saveAllAndFlush(List.of(s1, s2, s3, s4));

        // Pre-revoke s3 so it must be skipped by the bulk update.
        s3.setRevokedAt(baseTime.plusSeconds(60));
        s3.setRevokeReason("ALREADY_REVOKED");
        refreshSessionRepository.saveAndFlush(s3);

        int updated = refreshSessionRepository.revokeUnrevokedByUserId(
                userOne.getId(), revocationTime, "LOGOUT_ALL");

        assertThat(updated).isEqualTo(2);

        RefreshSession r1 = refreshSessionRepository.findById(s1.getId()).orElseThrow();
        RefreshSession r2 = refreshSessionRepository.findById(s2.getId()).orElseThrow();
        RefreshSession r3 = refreshSessionRepository.findById(s3.getId()).orElseThrow();
        RefreshSession r4 = refreshSessionRepository.findById(s4.getId()).orElseThrow();

        assertThat(r1.getRevokedAt()).isEqualTo(revocationTime);
        assertThat(r1.getRevokeReason()).isEqualTo("LOGOUT_ALL");

        // Revoked even though expiresAt < revocationTime.
        assertThat(r2.getRevokedAt()).isEqualTo(revocationTime);
        assertThat(r2.getRevokeReason()).isEqualTo("LOGOUT_ALL");

        // Already revoked: not overwritten.
        assertThat(r3.getRevokeReason()).isEqualTo("ALREADY_REVOKED");
        assertThat(r3.getRevokedAt()).isEqualTo(baseTime.plusSeconds(60));

        // Belongs to a different user: untouched.
        assertThat(r4.getRevokedAt()).isNull();
        assertThat(r4.getRevokeReason()).isNull();
    }

    @Test
    void revokeUnrevokedByFamilyIdRevokesOnlyUnrevokedSessionsOfThatFamily() {
        User user = userRepository.saveAndFlush(
                new User(
                        "RefreshFamilyUser",
                        "refresh-family-user@example.com",
                        "test-fake-password-hash",
                        "Refresh Family User"
                )
        );

        UUID familyTarget = UUID.randomUUID();
        UUID familyOther = UUID.randomUUID();

        RefreshSession s1 = newSession(user, familyTarget, tokenHash(11), Instant.now().plusSeconds(3600));
        RefreshSession s2 = newSession(user, familyTarget, tokenHash(12), Instant.now().plusSeconds(3600));
        RefreshSession s3 = newSession(user, familyTarget, tokenHash(13), Instant.now().plusSeconds(3600));
        RefreshSession s4 = newSession(user, familyOther, tokenHash(14), Instant.now().plusSeconds(3600));

        refreshSessionRepository.saveAllAndFlush(List.of(s1, s2, s3, s4));

        // Pre-revoke s3 so it must be skipped by the bulk update.
        s3.setRevokedAt(Instant.now().plusSeconds(60));
        s3.setRevokeReason("ALREADY_REVOKED");
        refreshSessionRepository.saveAndFlush(s3);

        Instant revokeTime = Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.MICROS);

        int updated = refreshSessionRepository.revokeUnrevokedByFamilyId(
                familyTarget, revokeTime, "TOKEN_REUSE");

        assertThat(updated).isEqualTo(2);

        RefreshSession r1 = refreshSessionRepository.findById(s1.getId()).orElseThrow();
        RefreshSession r2 = refreshSessionRepository.findById(s2.getId()).orElseThrow();
        RefreshSession r3 = refreshSessionRepository.findById(s3.getId()).orElseThrow();
        RefreshSession r4 = refreshSessionRepository.findById(s4.getId()).orElseThrow();

        assertThat(r1.getRevokeReason()).isEqualTo("TOKEN_REUSE");
        assertThat(r1.getRevokedAt()).isEqualTo(revokeTime);
        assertThat(r2.getRevokeReason()).isEqualTo("TOKEN_REUSE");
        assertThat(r2.getRevokedAt()).isEqualTo(revokeTime);

        // Already revoked: not overwritten.
        assertThat(r3.getRevokeReason()).isEqualTo("ALREADY_REVOKED");

        // Belongs to a different family: untouched.
        assertThat(r4.getRevokedAt()).isNull();
        assertThat(r4.getRevokeReason()).isNull();
    }

    /**
     * Builds a refresh session with a fresh random id, the given family and
     * token hash, a fixed test user agent, no created IP, and the given
     * expiration. Created/revoked timestamps rely on the entity defaults and
     * the database constraints.
     */
    private RefreshSession newSession(User user, UUID familyId, String tokenHash, Instant expiresAt) {
        return new RefreshSession(
                UUID.randomUUID(),
                user,
                familyId,
                tokenHash,
                "integration-test",
                null,
                expiresAt
        );
    }

    /**
     * Returns a valid 64-character lowercase hexadecimal token hash that is
     * unique for the given seed. No plaintext token is ever generated.
     */
    private static String tokenHash(long seed) {
        return String.format("%016x", seed).repeat(4);
    }
}
