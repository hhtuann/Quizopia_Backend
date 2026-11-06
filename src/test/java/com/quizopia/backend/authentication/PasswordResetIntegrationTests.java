package com.quizopia.backend.authentication;

import com.quizopia.backend.account.application.PasswordResetService;
import com.quizopia.backend.notification.email.CapturePasswordResetMailSender;
import com.quizopia.backend.realtime.support.InMemoryLogAppender;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Password-reset flow tests (ADR-004 §"Reset persistence, intake and
 * delivery"). Deliberately NOT {@code @Transactional}: issuance and delivery
 * run on the bounded intake worker with its own committed transactions, so
 * test-managed rollback would race (and block FK checks) against them. Each
 * test uses unique usernames/emails; the container is ephemeral.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
class PasswordResetIntegrationTests extends AbstractAuthenticationIntegrationTests {

    private static final String OLD_PASSWORD = "Passw0rd!";
    private static final String NEW_PASSWORD = "NewPassw0rd!";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private CapturePasswordResetMailSender resetMailbox;
    @Autowired private PasswordResetService resets;

    private String username;
    private String email;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.now());
        username = "reset" + System.nanoTime();
        email = username + "@example.test";
    }

    @org.junit.jupiter.api.AfterEach
    void restoreClock() {
        // Expiry/replacement tests advance the clock; restore it so a later
        // context-sharing class never starts behind an advanced abuse clock.
        clock.setInstant(Instant.now());
    }

    // ---------- helpers ----------

    private MvcResult forgot(String address) throws Exception {
        return mockMvc.perform(post("/api/auth/password/forgot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + address + "\"}"))
                .andReturn();
    }

    private MvcResult reset(String token, String newPassword, String confirmPassword) throws Exception {
        return mockMvc.perform(post("/api/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword
                        + "\",\"confirmPassword\":\"" + confirmPassword + "\"}"))
                .andReturn();
    }

    /** Waits until the intake worker delivered the Nth reset mail, then extracts the fragment token. */
    private String awaitToken(String address, int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (resetMailbox.sendCountFor(address) >= expectedCount) {
                String url = resetMailbox.latestUrlFor(address);
                String prefix = "http://localhost:3000/reset-password#token=";
                assertThat(url).startsWith(prefix);
                assertThat(url.substring(prefix.length())).matches("[A-Za-z0-9_-]{43}");
                return url.substring(prefix.length());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("expected " + expectedCount + " reset mails for the address, saw "
                + resetMailbox.sendCountFor(address));
    }

    /** No further mail may arrive within a grace period long enough for any queued worker task. */
    private void assertNoFurtherMail(String address, int baseline) throws InterruptedException {
        Thread.sleep(500);
        assertThat(resetMailbox.sendCountFor(address)).isEqualTo(baseline);
    }

    private Map<String, Object> challengeRow(String rawToken) {
        return jdbc.queryForMap(
                "SELECT status, expires_at, email_binding, credential_epoch FROM password_reset_challenges"
                        + " WHERE token_hash = ?", resets.hashToken(rawToken));
    }

    private Long userId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private String errorCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asString();
    }

    // ---------- capture happy path ----------

    @Test
    void happyPathDeliversMailResetsPasswordAndRevokesEverySession() throws Exception {
        registerAndVerify(username, email, OLD_PASSWORD);
        MvcResult firstLogin = login(username, OLD_PASSWORD);
        MvcResult secondLogin = login(username, OLD_PASSWORD);
        String accessToken = extractAccessToken(firstLogin);
        String firstRefresh = extractRefreshCookieValue(firstLogin);
        String secondRefresh = extractRefreshCookieValue(secondLogin);

        try (InMemoryLogAppender serviceLogs = InMemoryLogAppender.attach(
                PasswordResetService.class.getName());
                InMemoryLogAppender intakeLogs = InMemoryLogAppender.attach(
                        com.quizopia.backend.account.application.PasswordResetIntakeService.class.getName())) {
            MvcResult forgotResult = forgot(email);
            assertThat(forgotResult.getResponse().getStatus()).isEqualTo(202);
            assertThat(forgotResult.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(forgotResult.getResponse().getContentAsString()).isEmpty();
            assertThat(forgotResult.getResponse().getHeader("Set-Cookie")).isNull();

            String rawToken = awaitToken(email, 1);
            // No raw token in any service/intake log line.
            assertThat(serviceLogs.joined()).doesNotContain(rawToken);
            assertThat(intakeLogs.joined()).doesNotContain(rawToken);
            assertThat(intakeLogs.joined()).doesNotContain(email);

            // No raw token at rest: only the 64-hex digest exists.
            Map<String, Object> row = challengeRow(rawToken);
            assertThat((String) row.get("status")).isEqualTo("PENDING");
            Integer directMatches = jdbc.queryForObject(
                    "SELECT count(*) FROM password_reset_challenges WHERE token_hash = ?",
                    Integer.class, rawToken);
            assertThat(directMatches).isZero();
            Integer auditLeaks = jdbc.queryForObject(
                    "SELECT count(*) FROM identity_audit_events WHERE details::text LIKE ?",
                    Integer.class, "%" + rawToken + "%");
            assertThat(auditLeaks).isZero();

            MvcResult resetResult = reset(rawToken, NEW_PASSWORD, NEW_PASSWORD);
            assertThat(resetResult.getResponse().getStatus()).isEqualTo(204);
            assertThat(resetResult.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(resetResult.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");

            assertThat((String) challengeRow(rawToken).get("status")).isEqualTo("CONSUMED");
            Integer resetsAudited = jdbc.queryForObject(
                    "SELECT count(*) FROM identity_audit_events WHERE user_id = ? AND event_type = 'PASSWORD_RESET'",
                    Integer.class, userId());
            assertThat(resetsAudited).isEqualTo(1);

            // Pre-existing authorization is fully invalidated…
            assertThat(mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + accessToken))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mockMvc.perform(refreshRequest(firstRefresh))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mockMvc.perform(refreshRequest(secondRefresh))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            // …the new password works and the old one no longer does.
            assertThat(login(username, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
            assertThat(login(username, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(401);

            // Replay of a consumed token: the single shared 400 code.
            MvcResult replay = reset(rawToken, "ThirdPassw0rd!", "ThirdPassw0rd!");
            assertThat(replay.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorCode(replay)).isEqualTo("AUTH_PASSWORD_RESET_INVALID");
        }
    }

    // ---------- expiry ----------

    @Test
    void expiredChallengeIsRejectedWithSharedCodeWithoutMutation() throws Exception {
        registerAndVerify(username, email, OLD_PASSWORD);
        forgot(email);
        String rawToken = awaitToken(email, 1);
        clock.advance(Duration.ofMinutes(16));
        MvcResult result = reset(rawToken, NEW_PASSWORD, NEW_PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo("AUTH_PASSWORD_RESET_INVALID");
        // Expiry is a predicate, not a state change.
        assertThat((String) challengeRow(rawToken).get("status")).isEqualTo("PENDING");
        assertThat(login(username, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- replacement ----------

    @Test
    void replacementSupersedesTheFirstPendingToken() throws Exception {
        registerAndVerify(username, email, OLD_PASSWORD);
        forgot(email);
        String firstToken = awaitToken(email, 1);
        clock.advance(Duration.ofSeconds(61)); // past the email cooldown
        forgot(email);
        String secondToken = awaitToken(email, 2);

        MvcResult old = reset(firstToken, NEW_PASSWORD, NEW_PASSWORD);
        assertThat(old.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(old)).isEqualTo("AUTH_PASSWORD_RESET_INVALID");
        assertThat((String) challengeRow(firstToken).get("status")).isEqualTo("SUPERSEDED");

        MvcResult fresh = reset(secondToken, NEW_PASSWORD, NEW_PASSWORD);
        assertThat(fresh.getResponse().getStatus()).isEqualTo(204);
        assertThat((String) challengeRow(secondToken).get("status")).isEqualTo("CONSUMED");
        assertThat(login(username, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- concurrency: exactly one winner ----------

    @Test
    void concurrentDoubleResetOfOneTokenHasExactlyOneWinner() throws Exception {
        registerAndVerify(username, email, OLD_PASSWORD);
        forgot(email);
        String rawToken = awaitToken(email, 1);
        int versionBefore = jdbc.queryForObject(
                "SELECT token_version FROM users WHERE username = ?", Integer.class, username);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Integer>> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    MvcResult result = reset(rawToken, NEW_PASSWORD, NEW_PASSWORD);
                    return result.getResponse().getStatus();
                }));
            }
            for (Future<Integer> outcome : outcomes) {
                outcome.get(20, TimeUnit.SECONDS);
            }
        }
        List<Integer> statuses = outcomes.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }).toList();
        assertThat(statuses).containsExactlyInAnyOrder(204, 400);
        // Exactly one credential mutation.
        int versionAfter = jdbc.queryForObject(
                "SELECT token_version FROM users WHERE username = ?", Integer.class, username);
        assertThat(versionAfter).isEqualTo(versionBefore + 1);
        assertThat(login(username, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- binding drift ----------

    @Test
    void bindingDriftRejectsEmailChangeEpochBumpAndLostVerification() throws Exception {
        String driftUsername = username;
        String driftEmail = email;
        registerAndVerify(driftUsername, driftEmail, OLD_PASSWORD);
        forgot(driftEmail);
        String rawToken = awaitToken(driftEmail, 1);

        // Email changed after issuance (challenge bound to the old address).
        jdbc.update("UPDATE users SET email = ? WHERE username = ?", "moved@example.test", driftUsername);
        MvcResult emailDrift = reset(rawToken, NEW_PASSWORD, NEW_PASSWORD);
        assertThat(emailDrift.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(emailDrift)).isEqualTo("AUTH_PASSWORD_RESET_INVALID");
        jdbc.update("UPDATE users SET email = ? WHERE username = ?", driftEmail, driftUsername);

        // Credential epoch bumped after issuance.
        jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE username = ?", driftUsername);
        MvcResult epochDrift = reset(rawToken, NEW_PASSWORD, NEW_PASSWORD);
        assertThat(epochDrift.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(epochDrift)).isEqualTo("AUTH_PASSWORD_RESET_INVALID");
        jdbc.update("UPDATE users SET token_version = token_version - 1 WHERE username = ?", driftUsername);

        // Verified-email proof withdrawn. The exact bound value is captured
        // first: restoring a different instant must also be rejected (drift).
        java.sql.Timestamp boundVerifiedAt = jdbc.queryForObject(
                "SELECT email_verified_at FROM users WHERE username = ?", java.sql.Timestamp.class, driftUsername);
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE username = ?", driftUsername);
        MvcResult newInstant = reset(rawToken, NEW_PASSWORD, NEW_PASSWORD);
        assertThat(newInstant.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(newInstant)).isEqualTo("AUTH_PASSWORD_RESET_INVALID");
        jdbc.update("UPDATE users SET email_verified_at = NULL WHERE username = ?", driftUsername);
        MvcResult proofDrift = reset(rawToken, NEW_PASSWORD, NEW_PASSWORD);
        assertThat(proofDrift.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(proofDrift)).isEqualTo("AUTH_PASSWORD_RESET_INVALID");

        // No drift → the same still-pending token succeeds.
        jdbc.update("UPDATE users SET email_verified_at = ? WHERE username = ?", boundVerifiedAt, driftUsername);
        MvcResult ok = reset(rawToken, NEW_PASSWORD, NEW_PASSWORD);
        assertThat(ok.getResponse().getStatus()).isEqualTo(204);
    }

    // ---------- lockout interplay ----------

    @Test
    void lockedAccountCannotConsumeUntilTheWindowElapsesThenCan() throws Exception {
        registerAndVerify(username, email, OLD_PASSWORD);
        forgot(email);
        String rawToken = awaitToken(email, 1);
        jdbc.update("UPDATE users SET locked_until = now() + interval '10 minutes' WHERE username = ?", username);
        MvcResult locked = reset(rawToken, NEW_PASSWORD, NEW_PASSWORD);
        assertThat(locked.getResponse().getStatus()).isEqualTo(423);
        assertThat(errorCode(locked)).isEqualTo("AUTH_ACCOUNT_LOCKED");
        // The rollback left the challenge PENDING: retry after the window.
        jdbc.update("UPDATE users SET locked_until = NULL WHERE username = ?", username);
        assertThat(reset(rawToken, NEW_PASSWORD, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
    }

    // ---------- reuse and validation ----------

    @Test
    void currentPasswordReuseAndBadBodiesAreRejected() throws Exception {
        registerAndVerify(username, email, OLD_PASSWORD);
        forgot(email);
        String rawToken = awaitToken(email, 1);

        // Resetting to the current password is rejected without mutation.
        MvcResult reuse = reset(rawToken, OLD_PASSWORD, OLD_PASSWORD);
        assertThat(reuse.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(reuse)).isEqualTo("AUTH_PASSWORD_RESET_INVALID");
        assertThat((String) challengeRow(rawToken).get("status")).isEqualTo("PENDING");

        assertThat(errorCode(reset(rawToken, "NewPassw0rd!", "Different1!")))
                .isEqualTo("AUTH_VALIDATION_ERROR");
        assertThat(reset(rawToken, "short", "short").getResponse().getStatus()).isEqualTo(400);
        assertThat(reset("", NEW_PASSWORD, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(reset("totally-unknown-token", NEW_PASSWORD, NEW_PASSWORD).getResponse().getStatus())
                .isEqualTo(400);
        MvcResult injected = mockMvc.perform(post("/api/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"" + NEW_PASSWORD
                        + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\",\"email\":\"attacker@example.test\"}"))
                .andReturn();
        assertThat(injected.getResponse().getStatus()).isEqualTo(400);

        // Syntax-only 400s on forgot, independent of existence.
        assertThat(forgot("not-an-email").getResponse().getStatus()).isEqualTo(400);
        assertThat(forgot("").getResponse().getStatus()).isEqualTo(400);
        MvcResult injectedForgot = mockMvc.perform(post("/api/auth/password/forgot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"token\":\"x\"}"))
                .andReturn();
        assertThat(injectedForgot.getResponse().getStatus()).isEqualTo(400);

        // The untouched token still works after all the rejected attempts.
        assertThat(reset(rawToken, NEW_PASSWORD, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
    }

    // ---------- intake timing sanity ----------

    @Test
    void secondRequestWithinCooldownIsThrottledButUniform() throws Exception {
        registerAndVerify(username, email, OLD_PASSWORD);
        MvcResult first = forgot(email);
        awaitToken(email, 1);
        MvcResult second = forgot(email);
        // Identical generic 202; the cooldown denial is invisible.
        assertThat(second.getResponse().getStatus()).isEqualTo(202);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(second.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(resetMailbox.sendCountFor(email)).isEqualTo(1);
        assertNoFurtherMail(email, 1);
    }
}
