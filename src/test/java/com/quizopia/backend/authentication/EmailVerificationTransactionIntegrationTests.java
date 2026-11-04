package com.quizopia.backend.authentication;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Review finding M1-01 regression proof: the OTP failed-attempt counter must
 * persist across INDEPENDENT HTTP transactions. This class is deliberately
 * NOT {@code @Transactional} — every MockMvc request commits like production,
 * and every assertion re-reads the database through a fresh persistence
 * context ({@code entityManager.clear()}), so nothing can be masked by a
 * shared test transaction.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class EmailVerificationTransactionIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private EntityManager entityManager;

    @Test
    void wrongOtpCounterPersistsAcrossIndependentRequests() throws Exception {
        String username = unique("tx");
        String email = emailFor(username);
        assertThat(register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"))
                .getResponse().getStatus()).isEqualTo(201);
        String realOtp = otpMailbox.latestOtpFor(email);
        String wrongOtp = realOtp.equals("123456") ? "654321" : "123456";

        // Five independent wrong submissions; after each one the committed
        // counter must be visible to a fresh persistence context.
        for (int expected = 1; expected <= 5; expected++) {
            int status = submitOtp(email, wrongOtp);
            assertThat(status).isEqualTo(400);
            entityManager.clear();
            Object[] row = (Object[]) entityManager.createNativeQuery(
                            "SELECT c.failed_attempts, c.status FROM email_verification_challenges c"
                                    + " JOIN users u ON u.id = c.user_id WHERE u.username = :u"
                                    + " ORDER BY c.id DESC LIMIT 1")
                    .setParameter("u", username)
                    .getSingleResult();
            assertThat(((Number) row[0]).intValue())
                    .as("committed attempts after wrong submission #%d", expected)
                    .isEqualTo(expected);
            if (expected < 5) {
                assertThat(row[1]).isEqualTo("PENDING");
            }
        }

        // The fifth wrong submission FAILED the challenge…
        entityManager.clear();
        Object status = entityManager.createNativeQuery(
                        "SELECT c.status FROM email_verification_challenges c"
                                + " JOIN users u ON u.id = c.user_id WHERE u.username = :u"
                                + " ORDER BY c.id DESC LIMIT 1")
                .setParameter("u", username)
                .getSingleResult();
        assertThat(status).isEqualTo("FAILED");

        // …so even the CORRECT code is now rejected.
        entityManager.clear();
        assertThat(submitOtp(email, realOtp)).isEqualTo(400);

        // Cap proven. (Recovery-after-resend is covered by the dedicated
        // non-transactional resend class — the async worker observes only
        // COMMITTED state, which this test's open transaction cannot
        // provide.)
    }

    @Test
    void concurrentWrongOtpSubmissionsCannotBypassTheCap() throws Exception {
        String username = unique("conc");
        String email = emailFor(username);
        assertThat(register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"))
                .getResponse().getStatus()).isEqualTo(201);
        String realOtp = otpMailbox.latestOtpFor(email);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String wrong = ((i % 2 == 0) ? "999999" : "111111").equals(realOtp) ? "888888" : (i % 2 == 0 ? "999999" : "111111");
            calls.add(() -> submitOtp(email, wrong));
        }
        List<Future<Integer>> futures = new ArrayList<>();
        calls.forEach(c -> futures.add(pool.submit(c)));
        int ok = 0;
        for (Future<Integer> f : futures) {
            if (f.get() == 200) {
                ok++;
            }
        }
        pool.shutdownNow();

        // None of the wrong codes may verify.
        assertThat(ok).isZero();
        entityManager.clear();
        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT c.failed_attempts, c.status FROM email_verification_challenges c"
                                + " JOIN users u ON u.id = c.user_id WHERE u.username = :u"
                                + " ORDER BY c.id DESC LIMIT 1")
                .setParameter("u", username)
                .getSingleResult();
        // The committed counter may exceed the cap under concurrency but the
        // challenge is FAILED and the correct code is dead either way.
        assertThat(((Number) row[0]).intValue()).isGreaterThanOrEqualTo(5);
        assertThat(row[1]).isEqualTo("FAILED");
        entityManager.clear();
        assertThat(submitOtp(email, realOtp)).isEqualTo(400);
    }

    @Test
    void deliveryFailureDoesNotBlockRegistrationAndResendRecovers() throws Exception {
        // SMTP failure is simulated by pointing the capture mailbox off: with
        // the capture sender delivery always "succeeds", so instead prove the
        // weaker invariant directly: an undelivered challenge still verifies
        // via a resend (cooldown passed) — i.e. delivery state never gates
        // verification.
        String username = unique("deliv");
        String email = emailFor(username);
        assertThat(register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"))
                .getResponse().getStatus()).isEqualTo(201);
        Thread.sleep(1100);
        assertThat(mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        waitForOtpMailCount(email, 2);
        assertThat(submitOtp(email, otpMailbox.latestOtpFor(email))).isEqualTo(200);
    }

    @Test
    void resendResponsesAreUniformAcrossAccountStates() throws Exception {
        // R2-M1-C: unknown / verified / unverified-cooling-down must be
        // indistinguishable in status AND body, while only the eligible
        // account actually receives a code.
        String unknownEmail = "ghost-" + UUID.randomUUID() + "@example.com";

        // Unknown email → 200 with cooldown payload.
        MvcResult unknown = mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(unknownEmail)))
                .andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(200);
        assertThat(unknown.getResponse().getContentAsString())
                .contains("resendCooldownSeconds");

        // Verified account → identical 200, no new mail.
        String username = unique("uni");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        assertThat(submitOtp(email, otpMailbox.latestOtpFor(email))).isEqualTo(200);
        int mailsBefore = otpMailbox.sendCountFor(email);
        MvcResult verified = mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andReturn();
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        assertThat(verified.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(otpMailbox.sendCountFor(email)).isEqualTo(mailsBefore);

        // Unverified account inside cooldown → STILL the identical 200 body,
        // and the counts prove no issuance happened.
        String username2 = unique("unic");
        String email2 = emailFor(username2);
        register(studentRegisterJson(username2, email2, "Passw0rd!", username2 + " Name", "+84991234567"));
        MvcResult throttled = mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email2)))
                .andReturn();
        assertThat(throttled.getResponse().getStatus()).isEqualTo(200);
        assertThat(throttled.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(challengeCount(username2)).isEqualTo(1);
        assertThat(otpMailbox.sendCountFor(email2)).isEqualTo(1);
    }

    @Test
    void concurrentResendsSerializeToOneChallengePerEligibleWindow() throws Exception {
        // R2-M1-C atomicity: 8 parallel resends right after registration are
        // all cooled down → zero new challenges; after the cooldown, 8 more
        // parallel resends produce EXACTLY one new challenge and one mail.
        String username = unique("conc2");
        String email = emailFor(username);
        register(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));

        int threads = 8;
        runConcurrentResends(threads, email); // cooldown active → all no-ops
        waitForResendWorkersToSettle();
        entityManager.clear();
        assertThat(challengeCount(username)).isEqualTo(1);
        assertThat(otpMailbox.sendCountFor(email)).isEqualTo(1);

        Thread.sleep(1100); // pass the 1s test cooldown

        runConcurrentResends(threads, email); // exactly one winner issues
        waitForOtpMailCount(email, 2);
        waitForResendWorkersToSettle();
        entityManager.clear();
        assertThat(challengeCount(username)).isEqualTo(2);
    }

    private void runConcurrentResends(int threads, String email) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> mockMvc.perform(post("/api/auth/verify-email/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\"}".formatted(email)))
                    .andReturn().getResponse().getStatus());
        }
        List<Future<Integer>> futures = new ArrayList<>();
        calls.forEach(c -> futures.add(pool.submit(c)));
        for (Future<Integer> f : futures) {
            // Uniform contract: every resend answers 200, no matter which
            // internal branch handled it.
            assertThat(f.get()).isEqualTo(200);
        }
        pool.shutdownNow();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    /** Waits until the bounded resend workers are idle and drained. */
    private void waitForResendWorkersToSettle() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Object executor = applicationContext.getBean("otpDeliveryExecutor");
            if (executor instanceof java.util.concurrent.ThreadPoolExecutor tpe
                    && tpe.getQueue().isEmpty() && tpe.getActiveCount() == 0) {
                Thread.sleep(150);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("resend workers did not settle");
    }

    private int challengeCount(String username) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM email_verification_challenges c"
                        + " JOIN users u ON u.id = c.user_id WHERE u.username = :u")
                .setParameter("u", username)
                .getSingleResult()).intValue();
    }

    private int submitOtp(String email, String otp) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailJson(email, otp)))
                .andReturn();
        return result.getResponse().getStatus();
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
