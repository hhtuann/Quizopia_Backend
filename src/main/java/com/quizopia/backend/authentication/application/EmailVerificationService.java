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

    private final com.quizopia.backend.classroom.application.RosterReconciliationService rosterReconciliation;
    private final EmailVerificationChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final OtpHasher otpHasher;
    private final OtpMailSender mailSender;
    private final EmailVerificationProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final CredentialSecurityService credentialSecurity;
    /** Background dispatch for the enumeration-sensitive resend path (R3-M1-03). */
    private final java.util.concurrent.Executor otpDeliveryExecutor;
    /**
     * Fixed HMAC target for timing equalization when no real challenge exists
     * (unknown email): burned once at construction, never a credential.
     */
    private final String dummyHash;

    public EmailVerificationService(
            com.quizopia.backend.classroom.application.RosterReconciliationService rosterReconciliation,
EmailVerificationChallengeRepository challengeRepository,
            UserRepository userRepository,
            OtpHasher otpHasher,
            OtpMailSender mailSender,
            EmailVerificationProperties properties,
            Clock clock,
            TransactionTemplate tx,
            java.util.concurrent.Executor otpDeliveryExecutor,
            CredentialSecurityService credentialSecurity) {
        this.rosterReconciliation = rosterReconciliation;
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.otpHasher = otpHasher;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
        this.otpDeliveryExecutor = otpDeliveryExecutor;
        this.credentialSecurity = credentialSecurity;
        this.dummyHash = otpHasher.hash("timing-equalizer");
    }

    /**
     * Verifies the OTP for the given email. Idempotent ONLY for the correct
     * code (a consumed challenge's hash still proves the retry is legitimate);
     * any wrong code fails identically whether or not the account exists.
     */
    public void verify(String email, String otp) {
        String candidate = normalizeOtp(otp);
        String address = email == null ? "" : email.trim();
        Long userId = userRepository.findIdByEmailIgnoreCase(address).orElse(null);
        if (userId == null) {
            otpHasher.matches(candidate, dummyHash);
            throw new AuthenticationException(AuthErrorCode.AUTH_OTP_INVALID);
        }
        AuthErrorCode failure = tx.execute(status -> {
            User user = credentialSecurity.lockUser(userId);
            if (!user.getEmail().trim().equalsIgnoreCase(address)) {
                otpHasher.matches(candidate, dummyHash);
                return AuthErrorCode.AUTH_OTP_INVALID;
            }
            EmailVerificationChallenge challenge = challengeRepository
                    .findFirstForUpdateByUserIdAndPurposeOrderByCreatedAtDesc(
                            userId, EmailChallengePurpose.ACCOUNT_VERIFICATION).orElse(null);
            Instant now = Instant.now(clock);
            if (challenge == null) {
                otpHasher.matches(candidate, dummyHash);
                return AuthErrorCode.AUTH_OTP_INVALID;
            }
            if (!otpHasher.matches(candidate, challenge.getOtpHash())) {
                if (challenge.getStatus() == EmailChallengeStatus.PENDING) {
                    challenge.incrementFailedAttempts();
                    if (challenge.getFailedAttempts() >= properties.getMaxFailedAttempts()) {
                        challenge.markFailed(now);
                        return AuthErrorCode.AUTH_OTP_MAX_ATTEMPTS;
                    }
                }
                return AuthErrorCode.AUTH_OTP_INVALID;
            }
            return switch (challenge.getStatus()) {
                case CONSUMED -> user.getEmailVerifiedAt() != null ? null : AuthErrorCode.AUTH_OTP_INVALID;
                case SUPERSEDED -> AuthErrorCode.AUTH_OTP_NOT_FOUND;
                case FAILED -> AuthErrorCode.AUTH_OTP_MAX_ATTEMPTS;
                case PENDING -> {
                    if (challenge.isExpired(now)) {
                        yield AuthErrorCode.AUTH_OTP_EXPIRED;
                    }
                    credentialSecurity.requireActiveUnlocked(user);
                    challenge.markConsumed(now);
                    if (user.getEmailVerifiedAt() == null) {
                        user.setEmailVerifiedAt(now);
                        // Explicit flush: when this tx JOINS an outer one, the
                        // proof write must reach the connection immediately —
                        // later statements on other EntityManagers (and native
                        // reads sharing this transaction) must observe it.
                        userRepository.saveAndFlush(user);
                    }
                    yield null;
                }
            };
        });
        if (failure != null) {
            throw new AuthenticationException(failure);
        }
        try {
            rosterReconciliation.reconcile(userId, address);
        } catch (RuntimeException ex) {
            log.error("Roster reconciliation failed for user {} ({})", userId, ex.getClass().getSimpleName());
        }
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
                    log.warn("Resend worker failed: {}", ex.getClass().getSimpleName());
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
        Long userId = userRepository.findIdByEmailIgnoreCase(email).orElse(null);
        if (userId == null) {
            log.debug("Resend requested for unknown email");
            return;
        }

        IssuedChallenge issued = tx.execute(status -> {
            // Serialize per-account: lock the user row BEFORE evaluating.
            User locked = credentialSecurity.lockUser(userId);
            if (!locked.getEmail().trim().equalsIgnoreCase(email.trim()) || locked.getEmailVerifiedAt() != null) {
                return null;
            }
            credentialSecurity.requireActiveUnlocked(locked);
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
            return new IssuedChallenge(challenge, otp, locked.getEmail(), locked.getDisplayName());
        });

        if (issued != null) {
            // Delivery runs INSIDE the worker (already off the HTTP path).
            deliverAfterCommit(issued);
        } else {
            log.debug("Resend throttled or ineligible for user {}", userId);
        }
    }

    private record IssuedChallenge(EmailVerificationChallenge challenge, String otp, String email, String displayName) {
    }

    // ---------- internals ----------

    /**
     * Registration-time issuance: one committed transaction creates the
     * challenge (superseding any PENDING predecessor under its row lock);
     * delivery runs strictly after that commit.
     */
    private boolean issueAndDeliver(User user, String requestIp) {
        String address = user.getEmail();
        String otp = otpHasher.generateOtp();
        IssuedChallenge created = tx.execute(status -> {
            User locked = credentialSecurity.lockUser(user.getId());
            if (!locked.getEmail().trim().equalsIgnoreCase(address.trim()) || locked.getEmailVerifiedAt() != null) {
                return null;
            }
            credentialSecurity.requireActiveUnlocked(locked);
            Instant now = Instant.now(clock);
            challengeRepository.findPendingForUpdate(
                            user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                    .ifPresent(pending -> {
                        pending.markSuperseded(now);
                        challengeRepository.saveAndFlush(pending);
                    });
            EmailVerificationChallenge challenge = challengeRepository.saveAndFlush(new EmailVerificationChallenge(
                    locked, EmailChallengePurpose.ACCOUNT_VERIFICATION,
                    otpHasher.hash(otp), now, now.plus(properties.getOtpTtl()), requestIp));
            return new IssuedChallenge(challenge, otp, locked.getEmail(), locked.getDisplayName());
        });
        return created != null && deliverAfterCommit(created);
    }

    /**
     * SMTP delivery AFTER the challenge commit (review M1-07); the
     * {@code delivered} flag is recorded in its own transaction. The OTP
     * plaintext lives only in this stack frame and the mail body.
     */
    private boolean deliverAfterCommit(IssuedChallenge issued) {
        EmailVerificationChallenge challenge = issued.challenge();
        boolean sent = mailSender.sendAccountVerificationOtp(
                issued.email(), issued.displayName(), issued.otp(),
                properties.getOtpTtl().toSeconds());
        if (sent) {
            // Targeted flag update only: the in-memory snapshot is stale by
            // now (SMTP ran outside the transaction) — merging it could
            // overwrite concurrent lifecycle changes (attempts/terminal
            // states) committed meanwhile.
            tx.executeWithoutResult(status ->
                    challengeRepository.markDeliveredById(challenge.getId()));
        } else {
            log.warn("OTP mail not delivered for user {} (resend is the recovery flow)",
                    challenge.getUser().getId());
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
