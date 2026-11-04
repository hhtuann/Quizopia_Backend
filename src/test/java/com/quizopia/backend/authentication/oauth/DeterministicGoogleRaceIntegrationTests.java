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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Review R2-M1-A regression proof: a DETERMINISTIC interleaving in which a
 * concurrent same-identity login commits (user + external identity) exactly
 * between this request's identity read and its email read. The service must
 * resolve the collision by the STABLE identity key and return the winner —
 * not reject the legitimate login with AUTH_OAUTH_EMAIL_CONFLICT.
 *
 * <p>Determinism: the interleaving is produced by a test subclass of the real
 * service ({@link Interleaved#emailExists}) that performs the winner's
 * committed INSERT at the precise seam — no sleeps, no barriers, no luck.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class,
        DeterministicGoogleRaceIntegrationTests.RaceTestConfig.class})
@ActiveProfiles("test")
class DeterministicGoogleRaceIntegrationTests {

    private static final String ISSUER = "https://fake-issuer.test";

    @Autowired
    private OauthIdentityService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @TestConfiguration(proxyBeanMethods = false)
    static class RaceTestConfig {

        /**
         * The real service with one overridden seam: when armed (per test),
         * the FIRST {@code emailExists} call for the armed address commits
         * the concurrent winner (user + identity for the SAME issuer/sub)
         * and only then answers — exactly the R2-M1-A window.
         */
        @Bean
        @Primary
        OauthIdentityService interleavedOauthIdentityService(
                ExternalIdentityRepository externalIdentityRepository,
                OauthLoginCodeRepository oauthLoginCodeRepository,
                UserRepository userRepository,
                UserRoleRepository userRoleRepository,
                RoleRepository roleRepository,
                PasswordHasher passwordHasher,
                RefreshTokenHasher sha256Hasher,
                WorkspaceProvisioningService workspaceProvisioningService,
                Clock clock,
                TransactionTemplate tx,
                JdbcTemplate jdbc) {
            return new Interleaved(externalIdentityRepository, oauthLoginCodeRepository,
                    userRepository, userRoleRepository, roleRepository, passwordHasher,
                    sha256Hasher, workspaceProvisioningService, clock, tx, jdbc);
        }
    }

    static class Interleaved extends OauthIdentityService {

        final JdbcTemplate jdbc;
        volatile String injectEmail;
        volatile String injectSubject;
        final AtomicBoolean injected = new AtomicBoolean(false);

        Interleaved(ExternalIdentityRepository externalIdentityRepository,
                OauthLoginCodeRepository oauthLoginCodeRepository,
                UserRepository userRepository,
                UserRoleRepository userRoleRepository,
                RoleRepository roleRepository,
                PasswordHasher passwordHasher,
                RefreshTokenHasher sha256Hasher,
                WorkspaceProvisioningService workspaceProvisioningService,
                Clock clock,
                TransactionTemplate tx,
                JdbcTemplate jdbc) {
            super(externalIdentityRepository, oauthLoginCodeRepository, userRepository,
                    userRoleRepository, roleRepository, passwordHasher, sha256Hasher,
                    workspaceProvisioningService, clock, tx);
            this.jdbc = jdbc;
        }

        /** Which emailExists invocation (1-based) performs the injection. */
        volatile int injectOnInvocation = 1;
        final java.util.concurrent.atomic.AtomicInteger invocations =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        protected boolean emailExists(String candidate) {
            String armed = injectEmail;
            int invocation = invocations.incrementAndGet();
            if (armed != null && candidate.equalsIgnoreCase(armed)
                    && invocation == injectOnInvocation
                    && injected.compareAndSet(false, true)) {
                // ---- The concurrent winner commits HERE (own transaction) ----
                jdbc.update("""
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           token_version, failed_login_attempts, password_changed_at,
                                           email_verified_at, login_requires_email_verification)
                        VALUES (?, ?, 'x', 'Race Winner', 'ACTIVE', 0, 0, now(), now(), FALSE)
                        """, "racewinner" + System.nanoTime() % 100000, armed);
                Long winnerId = jdbc.queryForObject(
                        "SELECT id FROM users WHERE email = ?", Long.class, armed);
                jdbc.update("""
                        INSERT INTO external_identities (user_id, provider, issuer, subject,
                                                         email_at_link, email_verified_at_link, linked_at)
                        VALUES (?, 'GOOGLE', ?, ?, ?, TRUE, now())
                        """, winnerId, ISSUER, injectSubject, armed);
                jdbc.update("""
                        INSERT INTO user_roles (user_id, role_id, assigned_at)
                        SELECT ?, r.id, now() FROM roles r WHERE r.code = 'STUDENT'
                        """, winnerId);
                // ----------------------------------------------------------------
            }
            return super.emailExists(candidate);
        }
    }

    @Test
    void sameIdentityCommittingBetweenReadsIsResolvedToWinnerNotConflict() {
        String email = "race-" + UUID.randomUUID() + "@example.com";
        String subject = "sub-" + UUID.randomUUID();
        Interleaved armed = arm(email, subject);

        // The call whose identity read happens BEFORE the winner commits (the
        // seam commits it during the email check) — the R2-M1-A window.
        Long winnerId = armed.resolveGoogleUser(
                new ValidatedOidcIdentity(ISSUER, subject, email, true)).getId();

        entityManager.clear();
        Long storedWinner = jdbc.queryForObject(
                "SELECT user_id FROM external_identities WHERE subject = ?", Long.class, subject);
        assertThat(winnerId).isEqualTo(storedWinner);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM external_identities WHERE subject = ?", Integer.class, subject))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    @Test
    void sameIdentityCommittingInsideTheProvisioningTransactionResolvesToWinner() {
        // Review R3-M1-01: the winner commits between the INNER transaction's
        // findByIdentity and its emailExists — the inner read pair must
        // re-resolve by the stable identity key, not throw CONFLICT.
        String email = "inner-" + UUID.randomUUID() + "@example.com";
        String subject = "sub-" + UUID.randomUUID();
        Interleaved armed = arm(email, subject, 2);

        Long winnerId = armed.resolveGoogleUser(
                new ValidatedOidcIdentity(ISSUER, subject, email, true)).getId();

        entityManager.clear();
        Long storedWinner = jdbc.queryForObject(
                "SELECT user_id FROM external_identities WHERE subject = ?", Long.class, subject);
        assertThat(winnerId).isEqualTo(storedWinner);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM external_identities WHERE subject = ?", Integer.class, subject))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    @Test
    void foreignOwnerOfTheAddressIsStillAConflict() {
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
    }

    private Interleaved arm(String email, String subject) {
        return arm(email, subject, 1);
    }

    /**
     * @param injectOnInvocation 1 = outer orchestrator's emailExists (the
     *        R2 window); 2 = the emailExists INSIDE the provisioning
     *        transaction (the R3-M1-01 window).
     */
    private Interleaved arm(String email, String subject, int injectOnInvocation) {
        // The @Primary bean IS the Interleaved instance.
        assertThat(service).isInstanceOf(Interleaved.class);
        Interleaved interleaved = (Interleaved) service;
        interleaved.injectEmail = email;
        interleaved.injectSubject = subject;
        interleaved.injected.set(false);
        interleaved.invocations.set(0);
        interleaved.injectOnInvocation = injectOnInvocation;
        return interleaved;
    }
}
