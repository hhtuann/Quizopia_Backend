package com.quizopia.backend.authentication.application;

import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.EmailChallengePurpose;
import com.quizopia.backend.identity.domain.model.EmailChallengeStatus;
import com.quizopia.backend.identity.domain.model.EmailVerificationChallenge;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.EmailVerificationChallengeRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.notification.email.OtpMailSender;
import com.quizopia.backend.security.otp.OtpHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Email-verification OTP lifecycle (ADR-002 §2): issue, verify, resend.
 *
 * <h2>Transaction boundaries (review findings M1-01 / M1-07)</h2>
 * This service deliberately has NO class/method-wide {@code @Transactional}
 * around the public entry points. Each state change runs in its own short
 * transaction via {@link TransactionTemplate}, so:
 * <ul>
 *   <li>a failed-attempt counter (or a FAILED transition) is COMMITTED before
 *       the error is thrown — the 5-attempt cap genuinely holds across
 *       independent HTTP requests;</li>
 *   <li>SMTP delivery happens AFTER the challenge row is committed, so a mail
 *       outage can neither roll back the challenge nor hold a database
 *       transaction open (resend remains the recovery flow).</li>
 * </ul>
 *
 * <h2>Proof-bound verification (review finding M1-02)</h2>
 * Success is ONLY ever derived from the caller knowing the OTP — never from
 * account existence. Unknown email, wrong OTP on a verified account, and
 * wrong OTP on an unverified account all produce the same
 * {@code AUTH_OTP_INVALID} after a constant-time dummy comparison; the
 * idempotent retry of an ALREADY-CONSUMED correct code still succeeds because
 * the consumed challenge's hash is retained. The endpoint therefore cannot be
 * used to enumerate registered email addresses.
 *
 * <p>Timing (review R3-M1-03): the resend endpoint performs no synchronous
 * SMTP — delivery is dispatched to a background executor after the issuing
 * transaction commits, so every reachable state costs only an HMAC
 * comparison plus at most one small transaction. This is a documented
 * bounded mitigation (the dominant latency term is removed from the
 * request path), not a constant-time claim; the regression test drives a
 * deliberately delayed SMTP double and asserts the eligible band stays
 * flat.
 *
 * <p>Security properties retained: HMAC-SHA256 storage, constant-time
 * compare, one PENDING challenge per account (resend supersedes),
 * per-challenge failed-attempt cap, per-email/per-IP issuance rate limits,
 * row-locked consumption (exactly-once), OTP plaintext never logged, never
 * stored, never returned.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final String MASKED_IP_LOG = "<ip>";

    private final EmailVerificationChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final OtpHasher otpHasher;
    private final OtpMailSender mailSender;
    private final EmailVerificationProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    /** Background dispatch for the enumeration-sensitive resend path (R3-M1-03). */
    private final java.util.concurrent.Executor otpDeliveryExecutor;
    /**
     * Fixed HMAC target for timing equalization when no real challenge exists
     * (unknown email): burned once at construction, never a credential.
     */
    private final String dummyHash;

    public EmailVerificationService(EmailVerificationChallengeRepository challengeRepository,
            UserRepository userRepository,
            OtpHasher otpHasher,
            OtpMailSender mailSender,
            EmailVerificationProperties properties,
            Clock clock,
            TransactionTemplate tx,
            java.util.concurrent.Executor otpDeliveryExecutor) {
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.otpHasher = otpHasher;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
        this.otpDeliveryExecutor = otpDeliveryExecutor;
        this.dummyHash = otpHasher.hash("timing-equalizer");
    }

    /**
     * Verifies the OTP for the given email. Idempotent ONLY for the correct
     * code (a consumed challenge's hash still proves the retry is legitimate);
     * any wrong code fails identically whether or not the account exists.
     */
    public void verify(String email, String otp) {
        Instant now = Instant.now(clock);
        String candidate = normalizeOtp(otp);
        String normalizedEmail = email == null ? "" : email.trim();

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);
        Optional<EmailVerificationChallenge> challengeOpt = userOpt.flatMap(user ->
                challengeRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
                        user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION));

        if (userOpt.isEmpty() || challengeOpt.isEmpty()) {
            // Anti-enumeration: spend the same compare time as a real lookup,
            // then answer exactly like a wrong OTP.
            otpHasher.matches(candidate, dummyHash);
            throw new AuthenticationException(AuthErrorCode.AUTH_OTP_INVALID);
        }
        User user = userOpt.get();
        EmailVerificationChallenge challenge = challengeOpt.get();

        if (!otpHasher.matches(candidate, challenge.getOtpHash())) {
            rejectWrongOtp(user, challenge, now);
        }

        // The caller proved knowledge of the code — now the challenge state
        // decides the outcome.
        switch (challenge.getStatus()) {
            case CONSUMED -> {
                // Idempotent retry of an already-consumed correct code. The
                // consume transaction always sets email_verified_at together
                // with the transition, so this is a genuine success.
                log.debug("Idempotent OTP re-verification for user {}", user.getId());
                return;
            }
            case SUPERSEDED -> throw new AuthenticationException(AuthErrorCode.AUTH_OTP_NOT_FOUND);
            case FAILED -> throw new AuthenticationException(AuthErrorCode.AUTH_OTP_MAX_ATTEMPTS);
            case PENDING -> {
                if (challenge.isExpired(now)) {
                    throw new AuthenticationException(AuthErrorCode.AUTH_OTP_EXPIRED);
                }
                consumeChallenge(user, challenge, now);
            }
        }
    }

    /**
     * Records a wrong-OTP attempt in a transaction that COMMITS before the
     * error surfaces (review M1-01), under the challenge row lock so
     * concurrent wrong guesses cannot bypass the cap. The error code is
     * re-derived from the committed state.
     */
    private void rejectWrongOtp(User user, EmailVerificationChallenge challenge, Instant now) {
        if (challenge.getStatus() != EmailChallengeStatus.PENDING) {
            // Terminal challenge + wrong code: nothing to count.
            throw new AuthenticationException(AuthErrorCode.AUTH_OTP_INVALID);
        }
        // Commit the counter first; throw only afterwards.
        tx.executeWithoutResult(status -> {
            EmailVerificationChallenge locked = challengeRepository
                    .findPendingForUpdate(user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                    .orElse(null);
            if (locked == null || !locked.getId().equals(challenge.getId())) {
                // A concurrent resend superseded it; the old challenge is no
                // longer the live one. Nothing to count against.
                return;
            }
            locked.incrementFailedAttempts();
            if (locked.getFailedAttempts() >= properties.getMaxFailedAttempts()) {
                locked.markFailed(now);
            }
            challengeRepository.saveAndFlush(locked);
        });
        // Re-read the committed state (non-locking) to pick the error the
        // client sees — the locking query above requires a transaction.
        EmailVerificationChallenge fresh = challengeRepository
                .findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
                        user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                .orElse(challenge);
        if (fresh.getStatus() == EmailChallengeStatus.FAILED) {
            throw new AuthenticationException(AuthErrorCode.AUTH_OTP_MAX_ATTEMPTS);
        }
        throw new AuthenticationException(AuthErrorCode.AUTH_OTP_INVALID);
    }

    private void consumeChallenge(User user, EmailVerificationChallenge challenge, Instant now) {
        // Outcome is decided INSIDE the transaction (row lock) but converted
        // to an error only AFTER it returns: throwing inside a template
        // callback marks the (possibly joined) transaction rollback-only,
        // which would poison any surrounding transaction.
        Boolean consumed = tx.execute(status -> {
            EmailVerificationChallenge locked = challengeRepository
                    .findPendingForUpdate(user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                    .orElse(null);
            if (locked == null || !locked.getId().equals(challenge.getId())) {
                return Boolean.FALSE; // superseded in-flight → NOT_FOUND
            }
            if (locked.isExpired(Instant.now(clock))) {
                return null; // expired → EXPIRED error after the transaction
            }
            locked.markConsumed(Instant.now(clock));
            challengeRepository.saveAndFlush(locked);
            if (user.getEmailVerifiedAt() == null) {
                user.setEmailVerifiedAt(Instant.now(clock));
                userRepository.saveAndFlush(user);
            }
            return Boolean.TRUE;
        });
        if (Boolean.TRUE.equals(consumed)) {
            log.info("Email verified for user {}", user.getId());
            return;
        }
        if (consumed == null) {
            throw new AuthenticationException(AuthErrorCode.AUTH_OTP_EXPIRED);
        }
        throw new AuthenticationException(AuthErrorCode.AUTH_OTP_NOT_FOUND);
    }

    /**
     * Creates the initial ACCOUNT_VERIFICATION challenge for a just-registered
     * user. The challenge row is committed FIRST; delivery happens after that
     * commit and its result is recorded in a separate small transaction, so
     * SMTP latency/failure never blocks or rolls back account creation.
     *
     * @return {@code true} when the OTP mail was delivered
     */
    public boolean issueForNewRegistration(User user, String requestIp) {
        // No rate-limit throw here: registration checks issuance eligibility
        // BEFORE persisting the account (see assertRegistrationIssuanceAllowed);
        // by the time this runs, denying would be inconsistent with a row
        // that already committed.
        return issueAndDeliver(user, requestIp);
    }

    /**
     * Registration-time gate (review R3-M1-02): throws AUTH_RATE_LIMITED
     * BEFORE any user row exists, so a denied registration is exactly a
     * non-event (no partial state, retryable after the window).
     */
    public void assertRegistrationIssuanceAllowed(String requestIp) {
        assertIpRateLimit(requestIp);
    }

    /**
     * PUBLIC resend intake (reviews R2-M1-C + round 4): performs NO
     * account-specific work whatsoever — one constant HMAC (uniform CPU for
     * every request) and a bounded-queue enqueue. The response is a constant
     * 200 body carrying the configured cooldown window for every reachable
     * state, so status, body AND timing are structurally decoupled from
     * account state (not merely empirically similar). Processing — including
     * the decision of whether the address is unknown, verified, cooled down
     * or eligible — happens on the background worker
     * ({@link #processResend(String, String)}).
     *
     * <p>Bounded intake: when the worker queue is full the request is dropped
     * with a WARN log (no PII) and the same uniform 200; recovery is the
     * resend endpoint itself (retry after the cooldown).
     *
     * @return the configured cooldown seconds (constant, not account-derived)
     */
    public long resend(String email, String requestIp) {
        // Constant-cost normalization work for every request.
        otpHasher.matches("resend-intake-equalizer", dummyHash);
        final String normalized = email == null ? "" : email.trim();
        final String ip = requestIp;
        try {
            // execute() (NOT a raw queue offer): it spawns the pool's worker
            // threads on demand and throws RejectedExecutionException once
            // the bounded queue is full.
            otpDeliveryExecutor.execute(() -> {
                try {
                    processResend(normalized, ip);
                } catch (RuntimeException ex) {
                    // Worker failures never propagate to the (already-answered)
                    // client; recovery is a retry after the cooldown.
                    log.warn("Resend worker failed: {}", ex.toString());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            log.warn("Resend intake dropped: worker queue full (capacity bounded)");
        }
        return properties.getResendCooldown().toSeconds();
    }

    /**
     * Background resend state machine: unknown → nothing; verified → nothing;
     * unverified → serialized (user-row lock) cooldown/window evaluation,
     * issuing at most one challenge per eligible window, then synchronous
     * delivery (already off the HTTP path).
     */
    void processResend(String email, String requestIp) {
        if (email == null || email.isBlank()) {
            return;
        }
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            log.debug("Resend requested for unknown email");
            return;
        }
        User user = userOpt.get();
        if (user.getEmailVerifiedAt() != null) {
            // Verified accounts never receive codes.
            return;
        }

        IssuedChallenge issued = tx.execute(status -> {
            // Serialize per-account: lock the user row BEFORE evaluating.
            User locked = userRepository.findForUpdateById(user.getId()).orElse(null);
            if (locked == null || locked.getEmailVerifiedAt() != null) {
                return null; // vanished / verified concurrently → silent no-op
            }
            Instant now = Instant.now(clock);
            boolean cooldownOk = challengeRepository
                    .findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
                            locked.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                    .map(latest -> !now.isBefore(
                            latest.getCreatedAt().plus(properties.getResendCooldown())))
                    .orElse(true);
            boolean emailWindowOk = challengeRepository.countByUserEmailSince(
                    locked.getEmail(), now.minus(properties.getEmailWindow()))
                    < properties.getMaxChallengesPerEmail();
            boolean ipOk = requestIp == null || requestIp.isBlank()
                    || challengeRepository.countByRequestIpAndCreatedAtGreaterThanEqual(
                            requestIp, now.minus(properties.getIpWindow()))
                       < properties.getMaxChallengesPerIp();
            if (!(cooldownOk && emailWindowOk && ipOk)) {
                return null; // throttled → uniform intake already answered 200
            }
            String otp = otpHasher.generateOtp();
            // Supersede any PENDING predecessor first (flush before insert so
            // the partial unique index never sees two PENDING rows).
            challengeRepository.findPendingForUpdate(
                            locked.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                    .ifPresent(pending -> {
                        pending.markSuperseded(now);
                        challengeRepository.saveAndFlush(pending);
                    });
            EmailVerificationChallenge challenge = challengeRepository.saveAndFlush(
                    new EmailVerificationChallenge(
                            locked, EmailChallengePurpose.ACCOUNT_VERIFICATION,
                            otpHasher.hash(otp), now, now.plus(properties.getOtpTtl()), requestIp));
            return new IssuedChallenge(challenge, otp);
        });

        if (issued != null) {
            // Delivery runs INSIDE the worker (already off the HTTP path).
            deliverAfterCommit(issued.challenge(), issued.otp());
        } else {
            log.debug("Resend throttled or ineligible for user {}", user.getId());
        }
    }

    private record IssuedChallenge(EmailVerificationChallenge challenge, String otp) {
    }

    // ---------- internals ----------

    /**
     * Registration-time issuance: one committed transaction creates the
     * challenge (superseding any PENDING predecessor under its row lock);
     * delivery runs strictly after that commit.
     */
    private boolean issueAndDeliver(User user, String requestIp) {
        Instant now = Instant.now(clock);
        String otp = otpHasher.generateOtp();
        EmailVerificationChallenge created = tx.execute(status -> {
            challengeRepository.findPendingForUpdate(
                            user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                    .ifPresent(pending -> {
                        pending.markSuperseded(now);
                        challengeRepository.saveAndFlush(pending);
                    });
            return challengeRepository.saveAndFlush(new EmailVerificationChallenge(
                    user, EmailChallengePurpose.ACCOUNT_VERIFICATION,
                    otpHasher.hash(otp), now, now.plus(properties.getOtpTtl()), requestIp));
        });
        return deliverAfterCommit(created, otp);
    }

    /**
     * SMTP delivery AFTER the challenge commit (review M1-07); the
     * {@code delivered} flag is recorded in its own transaction. The OTP
     * plaintext lives only in this stack frame and the mail body.
     */
    private boolean deliverAfterCommit(EmailVerificationChallenge challenge, String otp) {
        User user = challenge.getUser();
        boolean sent = mailSender.sendAccountVerificationOtp(
                user.getEmail(), user.getDisplayName(), otp,
                properties.getOtpTtl().toSeconds());
        if (sent) {
            challenge.markDelivered();
            tx.executeWithoutResult(status -> challengeRepository.save(challenge));
        } else {
            log.warn("OTP mail not delivered for user {} (resend is the recovery flow)",
                    user.getId());
        }
        return sent;
    }

    private void assertIpRateLimit(String requestIp) {
        if (requestIp == null || requestIp.isBlank()) {
            return;
        }
        Instant cutoff = Instant.now(clock).minus(properties.getIpWindow());
        long issued = challengeRepository.countByRequestIpAndCreatedAtGreaterThanEqual(requestIp, cutoff);
        if (issued >= properties.getMaxChallengesPerIp()) {
            log.warn("OTP issuance rate limit hit for {}", MASKED_IP_LOG);
            throw new AuthenticationException(AuthErrorCode.AUTH_RATE_LIMITED);
        }
    }

    private static String normalizeOtp(String otp) {
        if (otp == null) {
            return "";
        }
        return otp.trim();
    }
}
