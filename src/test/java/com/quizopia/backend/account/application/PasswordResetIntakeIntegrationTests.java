package com.quizopia.backend.account.application;

import com.quizopia.backend.notification.email.CapturePasswordResetMailSender;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Forgot-password intake tests (ADR-004 §"Reset persistence, intake and
 * delivery"): eligibility matrix, response uniformity across every reachable
 * state (including throttle and queue saturation) and the abuse-bucket
 * window/cooldown state machine. Lives in the application package so the
 * bucket observability accessor is reachable. Not {@code @Transactional} —
 * the intake worker commits on its own threads.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
class PasswordResetIntakeIntegrationTests extends AbstractAuthenticationIntegrationTests {

    private static final String PASSWORD = "Passw0rd!";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private CapturePasswordResetMailSender resetMailbox;
    @Autowired private PasswordResetAbuseService abuse;
    @Autowired @Qualifier("passwordResetIntakeExecutor") private ExecutorService intakeExecutor;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.now());
    }

    @org.junit.jupiter.api.AfterEach
    void restoreClock() {
        // Tests advance the mutable clock; reset it so later tests (in this or a
        // context-sharing class) never observe a "future" abuse-bucket timestamp.
        clock.setInstant(Instant.now());
    }

    // ---------- helpers ----------

    private MvcResult forgot(String address) throws Exception {
        return mockMvc.perform(post("/api/auth/password/forgot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + address + "\"}"))
                .andReturn();
    }

    private String registeredVerified(String username) throws Exception {
        String address = username + "@example.test";
        registerAndVerify(username, address, PASSWORD);
        return address;
    }

    private void assertUniformAccepted(MvcResult result, MvcResult baseline) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getContentAsString())
                .isEqualTo(baseline.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeader("Cache-Control"))
                .isEqualTo(baseline.getResponse().getHeader("Cache-Control"));
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    private int challengesFor(String username) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM password_reset_challenges p JOIN users u ON u.id = p.user_id"
                        + " WHERE u.username = ? AND p.status = 'PENDING'", Integer.class, username);
    }

    private void waitForDrain() throws InterruptedException {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) intakeExecutor;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (executor.getQueue().isEmpty() && executor.getActiveCount() == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("intake executor did not drain");
    }

    // ---------- eligibility matrix ----------

    @Test
    void onlyEnabledVerifiedUnlockedKnownAccountsGetAMailAndChallenge() throws Exception {
        String unknownState = "intake-unk-" + System.nanoTime();
        String googleOnly = "intake-google-" + System.nanoTime();
        String unverified = "intake-unverified-" + System.nanoTime();
        String locked = "intake-locked-" + System.nanoTime();
        String enabled = "intake-enabled-" + System.nanoTime();

        String unknownAddress = registeredVerified(unknownState);
        jdbc.update("UPDATE users SET local_credential_state = 'UNKNOWN' WHERE username = ?", unknownState);
        String googleAddress = registeredVerified(googleOnly);
        jdbc.update("UPDATE users SET local_credential_state = 'DISABLED' WHERE username = ?", googleOnly);
        // Unverified: registered but the OTP never consumed.
        register(studentRegisterJson(unverified, unverified + "@example.test", PASSWORD,
                unverified + " Name", "+84991234567"));
        String lockedAddress = registeredVerified(locked);
        jdbc.update("UPDATE users SET locked_until = now() + interval '10 minutes' WHERE username = ?", locked);
        String enabledAddress = registeredVerified(enabled);

        // Ineligible first, eligible last: when the last mail arrives every
        // earlier queued task has long finished.
        for (String address : new String[]{unknownAddress, googleAddress,
                unverified + "@example.test", lockedAddress}) {
            assertThat(forgot(address).getResponse().getStatus()).isEqualTo(202);
        }
        assertThat(forgot(enabledAddress).getResponse().getStatus()).isEqualTo(202);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && resetMailbox.sendCountFor(enabledAddress) < 1) {
            Thread.sleep(50);
        }
        assertThat(resetMailbox.sendCountFor(enabledAddress)).isEqualTo(1);
        Thread.sleep(500); // grace for any earlier task still mid-flight

        assertThat(resetMailbox.sendCountFor(unknownAddress)).isZero();
        assertThat(resetMailbox.sendCountFor(googleAddress)).isZero();
        assertThat(resetMailbox.sendCountFor(unverified + "@example.test")).isZero();
        assertThat(resetMailbox.sendCountFor(lockedAddress)).isZero();
        assertThat(challengesFor(unknownState)).isZero();
        assertThat(challengesFor(googleOnly)).isZero();
        assertThat(challengesFor(unverified)).isZero();
        assertThat(challengesFor(locked)).isZero();
        assertThat(challengesFor(enabled)).isEqualTo(1);
    }

    // ---------- uniformity including saturation ----------

    @Test
    void unknownIneligibleThrottledAndSaturatedRequestsAreIndistinguishable() throws Exception {
        String username = "uniform-" + System.nanoTime();
        String address = registeredVerified(username);

        MvcResult eligible = forgot(address);
        assertThat(eligible.getResponse().getStatus()).isEqualTo(202);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && resetMailbox.sendCountFor(address) < 1) {
            Thread.sleep(50);
        }
        assertThat(resetMailbox.sendCountFor(address)).isEqualTo(1);

        MvcResult unknown = forgot("nobody-" + System.nanoTime() + "@example.test");
        assertUniformAccepted(unknown, eligible);

        String googleOnly = "uniform-google-" + System.nanoTime();
        String googleAddress = registeredVerified(googleOnly);
        jdbc.update("UPDATE users SET local_credential_state = 'DISABLED' WHERE username = ?", googleOnly);
        MvcResult ineligible = forgot(googleAddress);
        assertUniformAccepted(ineligible, eligible);

        MvcResult throttled = forgot(address); // second request within the 60s cooldown
        assertUniformAccepted(throttled, eligible);
        assertThat(resetMailbox.sendCountFor(address)).isEqualTo(1);

        // Saturation: occupy both workers and fill the bounded queue, then
        // submit one more intake — the admission is consumed, the work dropped.
        ThreadPoolExecutor executor = (ThreadPoolExecutor) intakeExecutor;
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
        int guard = 0;
        while (executor.getQueue().remainingCapacity() > 0 && guard++ < 10_000) {
            executor.execute(blocker);
        }
        try {
            String saturatedAddress = "saturated-" + System.nanoTime() + "@example.test";
            MvcResult saturated = forgot(saturatedAddress);
            assertUniformAccepted(saturated, eligible);
            // The drop was synchronous: nothing will ever deliver.
            assertThat(resetMailbox.sendCountFor(saturatedAddress)).isZero();
            // The admission allowance was still consumed: an immediate retry
            // is cooldown-denied (no mail), proving last_admitted_at advanced.
            MvcResult probe = forgot(saturatedAddress);
            assertUniformAccepted(probe, eligible);
            assertThat(resetMailbox.sendCountFor(saturatedAddress)).isZero();
            PasswordResetAbuseService.BucketState bucket = abuse.emailBucket(saturatedAddress);
            assertThat(bucket).isNotNull();
            assertThat(bucket.attempts()).isEqualTo(2);
            assertThat(bucket.lastAdmittedAt()).isNotNull();
        } finally {
            release.countDown();
        }
        waitForDrain();

        assertThat(challengesFor(username)).isEqualTo(1);
        assertThat(resetMailbox.sendCountFor(address)).isEqualTo(1);
    }

    // ---------- bucket state machine ----------

    @Test
    void cooldownDenialIncrementsAttemptsWithoutExtendingTheCooldown() throws Exception {
        String username = "cooldown-" + System.nanoTime();
        String address = registeredVerified(username);

        forgot(address);
        PasswordResetAbuseService.BucketState first = abuse.emailBucket(address);
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.lastAdmittedAt()).isNotNull();

        forgot(address); // denied by the cooldown
        PasswordResetAbuseService.BucketState second = abuse.emailBucket(address);
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(second.lastAdmittedAt()).isEqualTo(first.lastAdmittedAt());
        assertThat(resetMailbox.sendCountFor(address)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(61));
        forgot(address); // admitted again
        PasswordResetAbuseService.BucketState third = abuse.emailBucket(address);
        assertThat(third.attempts()).isEqualTo(3);
        assertThat(third.lastAdmittedAt()).isAfter(first.lastAdmittedAt());

        // Elapsed one-hour window resets attempts but preserves last_admitted_at.
        clock.advance(Duration.ofMinutes(65));
        forgot(address);
        PasswordResetAbuseService.BucketState fourth = abuse.emailBucket(address);
        assertThat(fourth.attempts()).isEqualTo(1);
        // The reset preserved last_admitted_at (not nulled); this admitted
        // request then advanced it past the previous admission.
        assertThat(fourth.lastAdmittedAt()).isAfter(third.lastAdmittedAt());
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() + 1 < deadline && resetMailbox.sendCountFor(address) < 3) {
            Thread.sleep(50);
        }
        assertThat(resetMailbox.sendCountFor(address)).isEqualTo(3);
    }

    @Test
    void hourlyEmailCapDeniesAfterFiveAdmittedRequestsWhileStillCounting() throws Exception {
        String username = "cap-" + System.nanoTime();
        String address = registeredVerified(username);
        for (int i = 1; i <= 5; i++) {
            assertThat(forgot(address).getResponse().getStatus()).isEqualTo(202);
            int expected = i;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && resetMailbox.sendCountFor(address) < expected) {
                Thread.sleep(50);
            }
            assertThat(resetMailbox.sendCountFor(address)).isEqualTo(expected);
            clock.advance(Duration.ofSeconds(61));
        }
        // Sixth within the fixed hour window: cap denies, attempts saturate-increment.
        assertThat(forgot(address).getResponse().getStatus()).isEqualTo(202);
        assertThat(forgot(address).getResponse().getStatus()).isEqualTo(202);
        Thread.sleep(500);
        assertThat(resetMailbox.sendCountFor(address)).isEqualTo(5);
        assertThat(abuse.emailBucket(address).attempts()).isEqualTo(7);
        assertThat(challengesFor(username)).isEqualTo(1); // one pending challenge at a time
    }
}
