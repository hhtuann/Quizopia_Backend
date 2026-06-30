package com.hhtuann.backend.authentication;

import com.hhtuann.backend.authentication.application.RefreshService;
import com.hhtuann.backend.authentication.dto.ClientContext;
import com.hhtuann.backend.authentication.exception.AuthErrorCode;
import com.hhtuann.backend.authentication.exception.AuthenticationException;
import com.hhtuann.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.hhtuann.backend.testsupport.AuthScenario;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service-level concurrency test proving the pessimistic write lock serializes
 * two refreshes of the same opaque token: exactly one succeeds, the other trips
 * reuse detection and revokes the entire family. Uses real threads (not MockMvc)
 * so each call runs in its own transaction; has a hard timeout and is not flaky.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
class RefreshConcurrencyTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private AuthScenario authScenario;

    @Autowired
    private RefreshService refreshService;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void twoConcurrentRefreshesOfSameTokenYieldExactlyOneSuccess() throws Exception {
        String username = "conc-" + UUID.randomUUID().toString().substring(0, 8);
        String rawToken = authScenario.seedStudentAndReturnRefreshToken(username, "Passw0rd!");
        UUID familyId = familyIdOf(username);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger reuseDetected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            futures.add(exec.submit(() -> {
                try {
                    start.await();
                } catch (Exception ignored) {
                    // best-effort synchronization only
                }
                try {
                    refreshService.refresh(rawToken, new ClientContext("concurrency-test", "127.0.0.1"));
                    successes.incrementAndGet();
                } catch (AuthenticationException ex) {
                    if (ex.getErrorCode() == AuthErrorCode.AUTH_REFRESH_TOKEN_REUSE_DETECTED) {
                        reuseDetected.incrementAndGet();
                    }
                }
                return null;
            }));
        }

        for (Future<?> future : futures) {
            future.get();
        }
        exec.shutdown();
        assertThat(exec.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Exactly one refresh rotated; the loser detected reuse and revoked the family.
        assertThat(successes.get()).isEqualTo(1);
        assertThat(reuseDetected.get()).isEqualTo(1);

        Number unrevoked = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM refresh_sessions WHERE family_id = :f AND revoked_at IS NULL")
                .setParameter("f", familyId)
                .getSingleResult();
        assertThat(unrevoked.intValue()).isZero();
    }

    private UUID familyIdOf(String username) {
        return (UUID) entityManager.createNativeQuery(
                        "SELECT family_id FROM refresh_sessions WHERE user_id = (SELECT id FROM users WHERE username = :u)")
                .setParameter("u", username)
                .getSingleResult();
    }
}
