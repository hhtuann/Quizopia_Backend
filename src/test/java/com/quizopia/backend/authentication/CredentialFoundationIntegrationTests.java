package com.quizopia.backend.authentication;

import com.quizopia.backend.authentication.application.*;
import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.authentication.dto.LoginRequest;
import com.quizopia.backend.authentication.exception.*;
import com.quizopia.backend.identity.domain.model.*;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.security.password.PasswordHasher;
import com.quizopia.backend.testsupport.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
class CredentialFoundationIntegrationTests extends AbstractAuthenticationIntegrationTests {
    @Autowired UserRepository users;
    @Autowired PasswordHasher passwords;
    @Autowired CredentialSecurityService credentials;
    @Autowired LoginService loginService;
    @Autowired RefreshService refresh;
    @Autowired SessionIssuanceService issuance;
    @Autowired TransactionTemplate tx;
    @Autowired com.quizopia.backend.user.application.UserService admins;
    @Autowired com.quizopia.backend.identity.repository.RoleRepository roles;
    @Autowired com.quizopia.backend.identity.repository.UserRoleRepository userRoles;
    @Autowired EntityManager em;
    private static final String PASSWORD = "Passw0rd!";
    private static final ClientContext CLIENT = new ClientContext("foundation-test", "127.0.0.1");

    @Test
    void registrationExplicitlyEnablesAndLegacyProofPromotesOnlyAfterSuccess() throws Exception {
        String name = "reg-" + UUID.randomUUID().toString().substring(0, 8);
        registerAndVerify(name, name + "@example.com", PASSWORD);
        assertThat(users.findByUsernameIgnoreCase(name).orElseThrow().getLocalCredentialState()).isEqualTo(LocalCredentialState.ENABLED);
        User legacy = seed(LocalCredentialState.UNKNOWN);
        assertThatThrownBy(() -> loginService.login(new LoginRequest(legacy.getUsername(), "WrongPass!"), CLIENT))
                .isInstanceOf(AuthenticationException.class);
        assertThat(users.findById(legacy.getId()).orElseThrow().getLocalCredentialState()).isEqualTo(LocalCredentialState.UNKNOWN);
        loginService.login(new LoginRequest(legacy.getUsername(), PASSWORD), CLIENT);
        assertThat(users.findById(legacy.getId()).orElseThrow().getLocalCredentialState()).isEqualTo(LocalCredentialState.ENABLED);
        Number count = (Number) em.createNativeQuery("select count(*) from identity_audit_events where user_id=:id and event_type='CREDENTIAL_PROOF_PROMOTED' and details='{}'::jsonb and ip is null and user_agent is null")
                .setParameter("id", legacy.getId()).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    void disabledPasswordAndSharedLockoutAreEnforcedAndFailuresCommit() {
        User disabled = seed(LocalCredentialState.DISABLED);
        assertThat(proof(disabled.getId(), PASSWORD)).isEqualTo(AuthErrorCode.AUTH_LOCAL_CREDENTIAL_UNSUPPORTED);
        User user = seed(LocalCredentialState.UNKNOWN);
        for (int i = 0; i < 4; i++) assertThat(proof(user.getId(), "WrongPass!")).isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
        assertThatThrownBy(() -> loginService.login(new LoginRequest(user.getUsername(), "WrongPass!"), CLIENT))
                .isInstanceOf(AuthenticationException.class);
        assertThat(proof(user.getId(), PASSWORD)).isEqualTo(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
        assertThat(users.findById(user.getId()).orElseThrow().getFailedLoginAttempts()).isEqualTo(5);
        tx.executeWithoutResult(s -> credentials.lockUser(user.getId()).setLockedUntil(Instant.now().minusSeconds(1)));
        assertThat(proof(user.getId(), PASSWORD)).isNull();
        assertThat(users.findById(user.getId()).orElseThrow().getFailedLoginAttempts()).isZero();
    }

    @Test
    void mutationRevokesEveryFamilyAndOldJwtAndSecurityReadSurvivesTemporaryLock() throws Exception {
        User user = seed(LocalCredentialState.ENABLED);
        var first = loginService.login(new LoginRequest(user.getUsername(), PASSWORD), CLIENT);
        var second = loginService.login(new LoginRequest(user.getUsername(), PASSWORD), CLIENT);
        tx.executeWithoutResult(s -> credentials.lockUser(user.getId()).setLockedUntil(Instant.now().plusSeconds(60)));
        assertThat(mockMvc.perform(get("/api/account/security").header("Authorization", "Bearer " + first.tokenResponse().accessToken())).andReturn().getResponse().getStatus()).isEqualTo(200);
        tx.executeWithoutResult(s -> {
            User locked = credentials.lockUser(user.getId());
            locked.setLockedUntil(null);
            credentials.mutatePasswordLocked(locked, "Replacement1!", IdentityAuditEvent.PASSWORD_CHANGED);
        });
        assertThatThrownBy(() -> refresh.refresh(first.rawRefreshToken(), CLIENT)).isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(() -> refresh.refresh(second.rawRefreshToken(), CLIENT)).isInstanceOf(AuthenticationException.class);
        assertThat(mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + first.tokenResponse().accessToken())).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(users.findById(user.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
        assertThat(loginService.login(new LoginRequest(user.getUsername(), "Replacement1!"), CLIENT)).isNotNull();
    }

    @Test
    @Timeout(30)
    void waitingLoginRechecksPasswordAndWaitingRefreshCannotCrossMutation() throws Exception {
        User user = seed(LocalCredentialState.ENABLED);
        var session = loginService.login(new LoginRequest(user.getUsername(), PASSWORD), CLIENT);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch started = new CountDownLatch(2);
            Future<?>[] futures = new Future<?>[2];
            tx.executeWithoutResult(s -> {
                User locked = credentials.lockUser(user.getId());
                futures[0] = pool.submit(() -> {
                    started.countDown();
                    assertThatThrownBy(() -> loginService.login(new LoginRequest(user.getUsername(), PASSWORD), CLIENT)).isInstanceOf(AuthenticationException.class);
                });
                futures[1] = pool.submit(() -> {
                    started.countDown();
                    assertThatThrownBy(() -> refresh.refresh(session.rawRefreshToken(), CLIENT)).isInstanceOf(AuthenticationException.class);
                });
                await(started);
                credentials.mutatePasswordLocked(locked, "Replacement1!", IdentityAuditEvent.PASSWORD_CHANGED);
            });
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        }
        Number count = (Number) em.createNativeQuery("select count(*) from refresh_sessions where user_id=:id and revoked_at is null")
                .setParameter("id", user.getId()).getSingleResult();
        assertThat(count.intValue()).isZero();
    }

    @Test
    void staleIssuanceCannotRelabelPreMutationProofWithNewEpoch() {
        User stale = seed(LocalCredentialState.ENABLED);
        tx.executeWithoutResult(s -> credentials.mutatePasswordLocked(credentials.lockUser(stale.getId()), "Replacement1!", IdentityAuditEvent.PASSWORD_CHANGED));
        assertThatThrownBy(() -> issuance.issue(stale, CLIENT)).isInstanceOf(AuthenticationException.class);
    }

    @Test
    void adminEmailReassignmentDeletesConsumedAndPendingProofsEvenWhenChangedBack() throws Exception {
        User admin = seed(LocalCredentialState.ENABLED);
        tx.executeWithoutResult(s -> userRoles.saveAndFlush(new UserRole(users.findById(admin.getId()).orElseThrow(),
                roles.findByCode("SYSTEM_ADMIN").orElseThrow(), null, null)));
        String name = "email-" + UUID.randomUUID().toString().substring(0, 8);
        String original = name + "@example.com";
        registerAndVerify(name, original, PASSWORD);
        Long id = users.findIdByEmailIgnoreCase(original).orElseThrow();
        Number before = (Number) em.createNativeQuery("select count(*) from email_verification_challenges where user_id=:id")
                .setParameter("id", id).getSingleResult();
        assertThat(before.intValue()).isPositive();
        admins.updateUser(admin.getId(), id, new com.quizopia.backend.user.dto.UpdateUserRequest(null, name + "@changed.example.com"));
        admins.updateUser(admin.getId(), id, new com.quizopia.backend.user.dto.UpdateUserRequest(null, original));
        Number after = (Number) em.createNativeQuery("select count(*) from email_verification_challenges where user_id=:id")
                .setParameter("id", id).getSingleResult();
        assertThat(after.intValue()).isZero();
        assertThat(users.findById(id).orElseThrow().getEmailVerifiedAt()).isNull();
        String created = "admin-created-" + UUID.randomUUID().toString().substring(0, 8);
        admins.createUser(admin.getId(), new com.quizopia.backend.user.dto.CreateUserRequest(created,
                created + "@example.com", PASSWORD, "Created User",
                com.quizopia.backend.authentication.dto.AccountType.STUDENT, null));
        assertThat(users.findByUsernameIgnoreCase(created).orElseThrow().getLocalCredentialState())
                .isEqualTo(LocalCredentialState.ENABLED);
    }

    @Test
    void unknownIdentifierRetainsGenericInvalidCredentialsOutcome() {
        assertThatThrownBy(() -> loginService.login(new LoginRequest("missing-" + UUID.randomUUID(), PASSWORD), CLIENT))
                .isInstanceOfSatisfying(AuthenticationException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    private AuthErrorCode proof(Long id, String password) {
        return tx.execute(s -> credentials.reauthenticateLocked(credentials.lockUser(id), password));
    }
    private User seed(LocalCredentialState state) {
        return tx.execute(s -> {
            String name = "cred-" + UUID.randomUUID().toString().substring(0, 8);
            User user = new User(name, name + "@example.com", passwords.hash(PASSWORD), "Original");
            user.setLocalCredentialState(state);
            user.setLoginRequiresEmailVerification(false);
            return users.saveAndFlush(user);
        });
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
