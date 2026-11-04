package com.quizopia.backend.authentication;

import com.quizopia.backend.identity.repository.IdentityAuditEventRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import com.quizopia.backend.security.password.PasswordHasher;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Identity-M2 integration tests for the teacher self-upgrade (System #14 /
 * ADR-002 §8): grant semantics, STUDENT retention, idempotency, concurrency,
 * verified-email requirement, rejection paths, immediate authority effect,
 * and untouched historical data.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class TeacherSelfUpgradeIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private IdentityAuditEventRepository auditRepository;

    @Autowired
    private com.quizopia.backend.security.password.PasswordHasher passwordHasher;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate tx;

    @Autowired
    private EntityManager entityManager;

    @Test
    void verifiedStudentUpgradesToTeacherAndKeepsStudent() throws Exception {
        String username = unique("up");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));

        MvcResult result = mockMvc.perform(post("/api/auth/upgrade-teacher")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Long userId = userIdOf(username);
        assertThat(userRoleRepository.findActiveRoleCodesByUserId(userId, java.time.Instant.now()))
                .containsExactlyInAnyOrder("STUDENT", "TEACHER");
        // The audit trail records the real upgrade with prior roles.
        assertThat(auditRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .hasSize(1)
                .first()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo("TEACHER_SELF_UPGRADE");
                    assertThat(event.getDetails().get("rolesBefore")).isEqualTo("STUDENT");
                });
    }

    @Test
    void studentOnlyCannotAccessTeacherApisBeforeUpgrade() throws Exception {
        String username = unique("gate");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));

        // Question banks are TEACHER-gated at the service layer (V11+):
        // a STUDENT-only token must be denied.
        MvcResult denied = mockMvc.perform(post("/api/question-banks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"code\":\"STU-1\",\"name\":\"Student Bank\"}"))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void upgradedStudentImmediatelyPassesTeacherAuthorization() throws Exception {
        String username = unique("instant");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        MvcResult loginResult = login(username, "Passw0rd!");
        String token = extractAccessToken(loginResult);

        mockMvc.perform(post("/api/auth/upgrade-teacher")
                .header("Authorization", "Bearer " + token));

        // SAME token (no re-login): authorities are DB-loaded per request.
        MvcResult me = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(me.getResponse().getContentAsString())
                .contains("TEACHER")
                .contains("STUDENT");
        assertThat(me.getResponse().getContentAsString()).contains("\"emailVerified\":true");
    }

    @Test
    void upgradeIsIdempotentAndAuditedDistinctly() throws Exception {
        String username = unique("idem");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));

        mockMvc.perform(post("/api/auth/upgrade-teacher").header("Authorization", "Bearer " + token));
        MvcResult second = mockMvc.perform(post("/api/auth/upgrade-teacher")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        Long userId = userIdOf(username);
        // Exactly one assignment…
        int assignments = ((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                                + " WHERE ur.user_id = :u AND r.code = 'TEACHER'")
                .setParameter("u", userId)
                .getSingleResult()).intValue();
        assertThat(assignments).isEqualTo(1);
        // …but both attempts are audited (real + idempotent marker).
        assertThat(auditRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .extracting("eventType")
                .containsExactly("TEACHER_SELF_UPGRADE_IDEMPOTENT", "TEACHER_SELF_UPGRADE");
    }

    @Test
    void concurrentUpgradesCreateExactlyOneAssignment() throws Exception {
        String username = unique("race");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger successes = new AtomicInteger();
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(() -> mockMvc.perform(post("/api/auth/upgrade-teacher")
                                .header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getStatus());
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> c : calls) {
                futures.add(pool.submit(c));
            }
            for (Future<Integer> f : futures) {
                if (f.get() == 200) {
                    successes.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
        }

        Long userId = userIdOf(username);
        assertThat(successes.get()).isEqualTo(threads);
        int assignments = ((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                                + " WHERE ur.user_id = :u AND r.code = 'TEACHER'")
                .setParameter("u", userId)
                .getSingleResult()).intValue();
        assertThat(assignments).isEqualTo(1);
        // STUDENT survives alongside the new TEACHER assignment.
        assertThat(userRoleRepository.findActiveRoleCodesByUserId(userId, java.time.Instant.now()))
                .containsExactlyInAnyOrder("STUDENT", "TEACHER");
    }

    @Test
    void unverifiedStudentIsRejected() throws Exception {
        String username = unique("unver");
        String email = emailFor(username);
        // Register WITHOUT verifying — login is blocked, so craft a token via
        // the service path is impossible; assert the login gate instead and
        // the endpoint rejects unauthenticated traffic below.
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        MvcResult blockedLogin = login(username, "Passw0rd!");
        assertThat(blockedLogin.getResponse().getStatus()).isEqualTo(403);
        assertThat(blockedLogin.getResponse().getContentAsString()).contains("AUTH_EMAIL_NOT_VERIFIED");
    }

    @Test
    void unauthenticatedUpgradeIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/upgrade-teacher"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void upgradeDoesNotTouchAttemptsOrWorkspaces() throws Exception {
        String username = unique("hist");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));
        Long userId = userIdOf(username);

        int workspacesBefore = ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM workspaces WHERE owner_user_id = " + userId).getSingleResult()).intValue();
        int userRowsBefore = ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM users WHERE id = " + userId).getSingleResult()).intValue();

        mockMvc.perform(post("/api/auth/upgrade-teacher").header("Authorization", "Bearer " + token));

        int workspacesAfter = ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM workspaces WHERE owner_user_id = " + userId).getSingleResult()).intValue();
        int userRowsAfter = ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM users WHERE id = " + userId).getSingleResult()).intValue();
        assertThat(workspacesAfter).isEqualTo(workspacesBefore);
        assertThat(userRowsAfter).isEqualTo(userRowsBefore).isEqualTo(1);
    }

    @Test
    void expiredTeacherAssignmentIsReactivatedAndEffective() throws Exception {
        String username = unique("reac");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));
        Long userId = userIdOf(username);

        // Review M2-01: an EXPIRED TEACHER assignment (revoked by time).
        tx.executeWithoutResult(status -> entityManager.createNativeQuery("""
                        INSERT INTO user_roles (user_id, role_id, assigned_by, assigned_at, expires_at)
                        SELECT :u, r.id, :u, now() - interval '2 days', now() - interval '1 day'
                        FROM roles r WHERE r.code = 'TEACHER'
                        """)
                .setParameter("u", userId)
                .executeUpdate());

        MvcResult result = mockMvc.perform(post("/api/auth/upgrade-teacher")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        entityManager.clear();
        Object expiresAt = entityManager.createNativeQuery(
                        "SELECT ur.expires_at FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                                + " WHERE ur.user_id = :u AND r.code = 'TEACHER'")
                .setParameter("u", userId)
                .getSingleResult();
        assertThat(expiresAt).as("expired assignment must be reactivated").isNull();
        assertThat(userRoleRepository.findActiveRoleCodesByUserId(userId, java.time.Instant.now()))
                .as("TEACHER must be EFFECTIVE after the upgrade")
                .containsExactlyInAnyOrder("STUDENT", "TEACHER");
        assertThat(auditRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .first()
                .satisfies(e -> assertThat(e.getEventType()).isEqualTo("TEACHER_SELF_UPGRADE_REACTIVATED"));
    }

    @Test
    void concurrentReactivationsYieldOneActiveAssignment() throws Exception {
        String username = unique("reacc");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));
        Long userId = userIdOf(username);

        tx.executeWithoutResult(status -> entityManager.createNativeQuery("""
                        INSERT INTO user_roles (user_id, role_id, assigned_by, assigned_at, expires_at)
                        SELECT :u, r.id, :u, now() - interval '2 days', now() - interval '1 day'
                        FROM roles r WHERE r.code = 'TEACHER'
                        """)
                .setParameter("u", userId)
                .executeUpdate());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> mockMvc.perform(post("/api/auth/upgrade-teacher")
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus());
        }
        int ok = 0;
        List<Future<Integer>> futures = new ArrayList<>();
        calls.forEach(c -> futures.add(pool.submit(c)));
        for (Future<Integer> f : futures) {
            if (f.get() == 200) ok++;
        }
        pool.shutdownNow();

        assertThat(ok).isEqualTo(threads);
        entityManager.clear();
        Integer rows = ((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                                + " WHERE ur.user_id = :u AND r.code = 'TEACHER'")
                .setParameter("u", userId).getSingleResult()).intValue();
        assertThat(rows).isEqualTo(1);
        Object expiresAt = entityManager.createNativeQuery(
                        "SELECT ur.expires_at FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                                + " WHERE ur.user_id = :u AND r.code = 'TEACHER'")
                .setParameter("u", userId).getSingleResult();
        assertThat(expiresAt).isNull();
    }

    @Test
    void legacyAccountWithoutEmailProofCannotUpgradeUntilVerified() throws Exception {
        // Review M2-02: legacy login compatibility does NOT satisfy the
        // verified-email prerequisite for self-upgrade.
        String username = "legacyup-" + UUID.randomUUID().toString().substring(0, 6);
        String email = username + "@legacy.example.com";
        tx.executeWithoutResult(status -> entityManager.createNativeQuery("""
                        INSERT INTO users (username, email, password_hash, display_name, status,
                                           token_version, failed_login_attempts, password_changed_at,
                                           login_requires_email_verification)
                        VALUES (:u, :e, :h, 'Legacy Upgrader', 'ACTIVE', 0, 0, now(), FALSE)
                        """)
                .setParameter("u", username)
                .setParameter("e", email)
                .setParameter("h", passwordHasher.hash("LegacyPassw0rd!"))
                .executeUpdate());

        // Legacy login works (compatibility flag)…
        MvcResult loginResult = login(username, "LegacyPassw0rd!");
        assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
        String token = extractAccessToken(loginResult);

        // …but self-upgrade requires a REAL email proof.
        MvcResult denied = mockMvc.perform(post("/api/auth/upgrade-teacher")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(denied.getResponse().getContentAsString()).contains("AUTH_EMAIL_NOT_VERIFIED");

        // After a real OTP verification, the upgrade succeeds.
        mockMvc.perform(post("/api/auth/verify-email/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)));
        waitForOtpMailCount(email, 1);
        mockMvc.perform(post("/api/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyEmailJson(email, otpMailbox.latestOtpFor(email))));
        MvcResult allowed = mockMvc.perform(post("/api/auth/upgrade-teacher")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(allowed.getResponse().getContentAsString()).contains("TEACHER");
    }

    // ---------- helpers ----------

    private Long userIdOf(String username) {
        return (Long) entityManager.createNativeQuery(
                        "SELECT id FROM users WHERE username = :u")
                .setParameter("u", username)
                .getSingleResult();
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
