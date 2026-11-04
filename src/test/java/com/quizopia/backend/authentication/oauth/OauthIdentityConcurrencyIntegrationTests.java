package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding M1-05 regression proof: concurrent Google first-logins for
 * the SAME (issuer, subject) must converge on exactly one Quizopia user,
 * identity, STUDENT grant and personal workspace — with independent
 * transactions (the test class is intentionally NOT transactional, so every
 * service call commits like a production callback).
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class OauthIdentityConcurrencyIntegrationTests {

    @Autowired
    private OauthIdentityService identityService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void eightConcurrentFirstLoginsProvisionExactlyOneUser() throws Exception {
        ValidatedOidcIdentity identity = identity();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Long>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> identityService.resolveGoogleUser(identity).getId());
        }
        List<Long> userIds = new ArrayList<>();
        List<Future<Long>> futures = new ArrayList<>();
        calls.forEach(c -> futures.add(pool.submit(c)));
        for (Future<Long> f : futures) {
            userIds.add(f.get());
        }
        pool.shutdownNow();

        // Every callback resolves the SAME user id…
        assertThat(userIds).hasSize(threads);
        assertThat(new java.util.HashSet<>(userIds)).hasSize(1);
        Long userId = userIds.get(0);

        entityManager.clear();
        // …and the database holds exactly one of everything.
        assertThat(count("SELECT count(*) FROM users WHERE id = " + userId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM external_identities ei JOIN users u ON u.id = ei.user_id"
                + " WHERE u.id = " + userId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                + " WHERE ur.user_id = " + userId + " AND r.code = 'STUDENT'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM workspaces WHERE owner_user_id = " + userId
                + " AND workspace_type = 'PERSONAL'")).isEqualTo(1);
    }

    @Test
    void differentSubjectWithSameEmailCannotTakeOver() {
        ValidatedOidcIdentity first = identity();
        Long ownerId = identityService.resolveGoogleUser(first).getId();

        ValidatedOidcIdentity impostor = new ValidatedOidcIdentity(
                first.issuer(), "sub-" + UUID.randomUUID(), first.email(), true);
        try {
            identityService.resolveGoogleUser(impostor);
            throw new AssertionError("impostor login must be rejected");
        } catch (com.quizopia.backend.authentication.exception.AuthenticationException expected) {
            assertThat(expected.getErrorCode())
                    .isEqualTo(com.quizopia.backend.authentication.exception.AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
        }
        entityManager.clear();
        assertThat(count("SELECT count(*) FROM external_identities ei JOIN users u ON u.id = ei.user_id"
                + " WHERE u.id = " + ownerId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM users WHERE id = " + ownerId)).isEqualTo(1);
    }

    @Test
    void differentIssuerDoesNotCollide() {
        ValidatedOidcIdentity googleA = identity();
        Long userIdA = identityService.resolveGoogleUser(googleA).getId();

        // Same subject, DIFFERENT issuer = a different identity key; with a
        // distinct email it provisions its own account (no collision).
        ValidatedOidcIdentity other = new ValidatedOidcIdentity(
                "https://other-issuer.test", googleA.subject(),
                "other" + UUID.randomUUID().toString().substring(0, 6) + "@example.com", true);
        Long userIdB = identityService.resolveGoogleUser(other).getId();

        assertThat(userIdB).isNotEqualTo(userIdA);
        entityManager.clear();
        assertThat(count("SELECT count(*) FROM external_identities ei JOIN users u ON u.id = ei.user_id"
                + " WHERE u.id IN (" + userIdA + "," + userIdB + ")")).isEqualTo(2);
    }

    @Test
    void repeatLoginHasStableIdentity() {
        ValidatedOidcIdentity identity = identity();
        Long first = identityService.resolveGoogleUser(identity).getId();
        Long second = identityService.resolveGoogleUser(identity).getId();
        Long third = identityService.resolveGoogleUser(identity).getId();
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    private int count(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).intValue();
    }

    private static ValidatedOidcIdentity identity() {
        return new ValidatedOidcIdentity(
                "https://fake-issuer.test",
                "sub-" + UUID.randomUUID(),
                "learner" + UUID.randomUUID().toString().substring(0, 6) + "@example.com",
                true);
    }
}
