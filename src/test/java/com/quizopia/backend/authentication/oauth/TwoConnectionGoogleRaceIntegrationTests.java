package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.repository.ExternalIdentityRepository;
import com.quizopia.backend.identity.repository.OauthLoginCodeRepository;
import com.quizopia.backend.identity.repository.RoleRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.security.password.PasswordHasher;
import com.quizopia.backend.security.token.RefreshTokenHasher;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.workspace.application.WorkspaceProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Review round 4 (R3-M1-01 evidence correction): deterministic interleaving
 * where the concurrent winner DEFINITIVELY COMMITS ON ANOTHER DATABASE
 * CONNECTION/TRANSACTION before the loser re-reads.
 *
 * <p>Coordination is explicit: the loser thread blocks inside the
 * {@code emailExists} seam (which runs INSIDE the provisioning transaction on
 * the loser's connection); a separate winner thread — with no ambient
 * transaction — commits user + identity + STUDENT grant through its OWN
 * {@link TransactionTemplate} (own pooled connection), signals a latch, and
 * only then does the loser proceed to its email read. The loser therefore
 * re-reads through its STILL-OPEN transaction under READ_COMMITTED and must
 * observe the winner's committed rows — read-your-own-writes is impossible:
 * the rows were never part of the loser's transaction.
 *
 * <p>This class is deliberately NOT {@code @Transactional}.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class,
        TwoConnectionGoogleRaceIntegrationTests.RaceTestConfig.class})
@ActiveProfiles("test")
class TwoConnectionGoogleRaceIntegrationTests {

    private static final String ISSUER = "https://fake-issuer.test";

    @Autowired
    private OauthIdentityService service;

    @Autowired
    private JdbcTemplate jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class RaceTestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        OauthIdentityService coordinatedOauthIdentityService(
                ExternalIdentityRepository externalIdentityRepository,
                OauthLoginCodeRepository oauthLoginCodeRepository,
                UserRepository userRepository,
                UserRoleRepository userRoleRepository,
                RoleRepository roleRepository,
                PasswordHasher passwordHasher,
                RefreshTokenHasher sha256Hasher,
                WorkspaceProvisioningService workspaceProvisioningService,
                java.time.Clock clock,
                com.quizopia.backend.authentication.application.CredentialSecurityService credentialSecurityService,
                TransactionTemplate tx,
                org.springframework.transaction.PlatformTransactionManager txManager,
                JdbcTemplate jdbc) {
            return new Coordinated(externalIdentityRepository, oauthLoginCodeRepository,
                    userRepository, userRoleRepository, roleRepository, passwordHasher,
                    sha256Hasher, workspaceProvisioningService, clock, credentialSecurityService, tx, txManager, jdbc);
        }
    }

    /**
     * Real service with one coordinated seam: at the chosen
     * {@code emailExists} invocation it BLOCKS until an independent thread
     * has committed the winner on its own connection/transaction.
     */
    static class Coordinated extends OauthIdentityService {

        final JdbcTemplate jdbc;
        final TransactionTemplate winnerTx; // used by the WINNER thread only
        volatile String armEmail;
        volatile String armSubject;
        volatile int armOnInvocation = 1;
        final AtomicBoolean armed = new AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger invocations =
                new java.util.concurrent.atomic.AtomicInteger();

        Coordinated(ExternalIdentityRepository externalIdentityRepository,
                OauthLoginCodeRepository oauthLoginCodeRepository,
                UserRepository userRepository,
                UserRoleRepository userRoleRepository,
                RoleRepository roleRepository,
                PasswordHasher passwordHasher,
                RefreshTokenHasher sha256Hasher,
                WorkspaceProvisioningService workspaceProvisioningService,
                java.time.Clock clock,
                com.quizopia.backend.authentication.application.CredentialSecurityService credentialSecurityService,
                TransactionTemplate tx,
                org.springframework.transaction.PlatformTransactionManager txManager,
                JdbcTemplate jdbc) {
            super(externalIdentityRepository, oauthLoginCodeRepository, userRepository,
                    userRoleRepository, roleRepository, passwordHasher, sha256Hasher,
                    workspaceProvisioningService, clock, tx, credentialSecurityService);
            this.jdbc = jdbc;
            this.winnerTx = new TransactionTemplate(txManager);
        }

        @Override
        protected boolean emailExists(String candidate) {
            String armedEmail = armEmail;
            int invocation = invocations.incrementAndGet();
            if (armedEmail != null && candidate.equalsIgnoreCase(armedEmail)
                    && invocation == armOnInvocation && armed.compareAndSet(false, true)) {
                commitWinnerOnIndependentConnection(armedEmail, armSubject);
            }
            return super.emailExists(candidate);
        }

        /**
         * Blocks the (loser) thread until a SEPARATE thread has committed the
         * winner rows on their own connection. If the winner thread fails,
         * the test fails loudly instead of passing vacuously.
         */
        private void commitWinnerOnIndependentConnection(String email, String subject) {
            CountDownLatch committed = new CountDownLatch(1);
            AtomicBoolean winnerFailed = new AtomicBoolean(false);
            Thread winner = new Thread(() -> {
                try {
                    // New thread ⇒ no ambient transaction ⇒ this template
                    // starts a NEW transaction on its OWN pooled connection.
                    winnerTx.executeWithoutResult(status -> {
                        jdbc.update("""
                                INSERT INTO users (username, email, password_hash, display_name, status,
                                                   token_version, failed_login_attempts, password_changed_at,
                                                   email_verified_at, login_requires_email_verification)
                                VALUES (?, ?, 'x', 'Race Winner', 'ACTIVE', 0, 0, now(), now(), FALSE)
                                """, "twocx" + System.nanoTime() % 100000, email);
                        Long winnerId = jdbc.queryForObject(
                                "SELECT id FROM users WHERE email = ?", Long.class, email);
                        jdbc.update("""
                                INSERT INTO external_identities (user_id, provider, issuer, subject,
                                                                 email_at_link, email_verified_at_link, linked_at)
                                VALUES (?, 'GOOGLE', ?, ?, ?, TRUE, now())
                                """, winnerId, ISSUER, subject, email);
                        jdbc.update("""
                                INSERT INTO user_roles (user_id, role_id, assigned_at)
                                SELECT ?, r.id, now() FROM roles r WHERE r.code = 'STUDENT'
                                """, winnerId);
                    });
                    committed.countDown();
                } catch (RuntimeException ex) {
                    winnerFailed.set(true);
                    committed.countDown();
                }
            }, "race-winner-thread");
            winner.start();
            try {
                assertThat(committed.await(10, TimeUnit.SECONDS))
                        .as("winner thread must complete within 10s")
                        .isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted awaiting winner commit", e);
            }
            assertThat(winnerFailed.get()).as("winner thread must commit cleanly").isFalse();
            // The winner's transaction has COMMITTED on another connection
            // before this method returns and the loser re-reads.
        }
    }

    private Coordinated arm(String email, String subject, int invocation) {
        assertThat(service).isInstanceOf(Coordinated.class);
        Coordinated coordinated = (Coordinated) service;
        coordinated.armEmail = email;
        coordinated.armSubject = subject;
        coordinated.armOnInvocation = invocation;
        coordinated.armed.set(false);
        coordinated.invocations.set(0);
        return coordinated;
    }

    @Test
    void outerWindowWinnerCommittedOnAnotherConnectionResolvesToWinner() {
        String email = "twoconn-" + UUID.randomUUID() + "@example.com";
        String subject = "sub-" + UUID.randomUUID();
        arm(email, subject, 1); // first emailExists = outer orchestrator window

        Long resolvedId = service.resolveGoogleUser(
                new ValidatedOidcIdentity(ISSUER, subject, email, true)).getId();

        // The resolved user IS the winner committed by the other thread.
        Long winnerId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        assertThat(resolvedId).isEqualTo(winnerId);
        // Exactly one of everything; the loser inserted nothing extra.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM external_identities WHERE subject = ?", Integer.class, subject))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                        + " JOIN users u ON u.id = ur.user_id WHERE u.email = ? AND r.code = 'STUDENT'",
                Integer.class, email)).isEqualTo(1);
    }

    @Test
    void innerWindowWinnerCommittedOnAnotherConnectionResolvesToWinner() {
        String email = "twoconn-inner-" + UUID.randomUUID() + "@example.com";
        String subject = "sub-" + UUID.randomUUID();
        arm(email, subject, 2); // second emailExists = inside provisioning tx

        Long resolvedId = service.resolveGoogleUser(
                new ValidatedOidcIdentity(ISSUER, subject, email, true)).getId();

        Long winnerId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        assertThat(resolvedId).isEqualTo(winnerId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM external_identities WHERE subject = ?", Integer.class, subject))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    @Test
    void rollbackCorrectnessForeignCollisionLeavesWinnerIntactAndNoDuplicate() {
        // A FOREIGN owner (password account, no identity for our subject):
        // the loser must surface the conflict AND leave the foreign row
        // untouched — commit/rollback correctness of the rejecting path.
        String email = "foreign-" + UUID.randomUUID() + "@example.com";
        jdbc.update("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   token_version, failed_login_attempts, password_changed_at,
                                   login_requires_email_verification)
                VALUES (?, ?, 'x', 'Foreign Owner', 'ACTIVE', 0, 0, now(), FALSE)
                """, "foreign" + System.nanoTime() % 100000, email);

        AuthenticationException ex = catchThrowableOfType(
                () -> service.resolveGoogleUser(new ValidatedOidcIdentity(
                        ISSUER, "sub-" + UUID.randomUUID(), email, true)),
                AuthenticationException.class);
        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isEqualTo(1);
        // No identity was linked to the foreign account.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM external_identities e JOIN users u ON u.id = e.user_id"
                        + " WHERE u.email = ?", Integer.class, email)).isZero();
    }
}
