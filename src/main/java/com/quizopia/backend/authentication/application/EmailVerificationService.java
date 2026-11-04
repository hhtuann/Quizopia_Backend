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
            TransactionTemplate tx) {
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.otpHasher = otpHasher;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
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

    /** Atomic consume + email-proof write under the challenge row lock. */
    private void consumeChallenge(User user, EmailVerificationChallenge challenge, Instant now) {
        try {
            tx.executeWithoutResult(status -> {
                EmailVerificationChallenge locked = challengeRepository
                        .findPendingForUpdate(user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                        .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_OTP_NOT_FOUND));
                if (!locked.getId().equals(challenge.getId())) {
                    // Superseded between the read and the lock.
                    throw new AuthenticationException(AuthErrorCode.AUTH_OTP_NOT_FOUND);
                }
                if (locked.isExpired(Instant.now(clock))) {
                    throw new AuthenticationException(AuthErrorCode.AUTH_OTP_EXPIRED);
                }
                locked.markConsumed(Instant.now(clock));
                challengeRepository.saveAndFlush(locked);
                if (user.getEmailVerifiedAt() == null) {
                    user.setEmailVerifiedAt(Instant.now(clock));
                    userRepository.saveAndFlush(user);
                }
            });
            log.info("Email verified for user {}", user.getId());
        } catch (org.springframework.dao.OptimisticLockingFailureException race) {
            // Two concurrent verifications of the same OTP: the loser sees the
            // winner's committed CONSUMED row on re-read — a success.
            EmailVerificationChallenge settled = challengeRepository
                    .findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
                            user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                    .orElse(challenge);
            if (settled.getStatus() == EmailChallengeStatus.CONSUMED) {
                return;
            }
            throw race;
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
        assertIpRateLimit(requestIp);
        return issueAndDeliver(user, requestIp);
    }

    /**
     * Re-issues the ACCOUNT_VERIFICATION OTP: supersedes the previous
     * challenge (committed transaction), enforces the per-account cooldown
     * plus per-email/per-IP rate limits, then attempts delivery after commit.
     * Unknown emails answer like successes so the endpoint cannot enumerate
     * accounts.
     *
     * @return seconds the client should wait before the next resend
     */
    public long resend(String email, String requestIp) {
        String normalized = email == null ? "" : email.trim();
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalized);
        if (userOpt.isEmpty()) {
            log.debug("Resend requested for unknown email");
            return properties.getResendCooldown().toSeconds();
        }
        User user = userOpt.get();
        if (user.getEmailVerifiedAt() != null) {
            return properties.getResendCooldown().toSeconds();
        }

        Instant now = Instant.now(clock);

        challengeRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
                        user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                .ifPresent(latest -> {
                    Instant eligibleAt = latest.getCreatedAt().plus(properties.getResendCooldown());
                    if (now.isBefore(eligibleAt)) {
                        long retryAfter = java.time.Duration.between(now, eligibleAt).toSeconds();
                        throw new AuthenticationException(AuthErrorCode.AUTH_OTP_RESEND_COOLDOWN,
                                "Please wait " + retryAfter + "s before requesting a new code");
                    }
                });

        assertEmailRateLimit(user.getEmail(), now);
        assertIpRateLimit(requestIp);

        return issueAndDeliver(user, requestIp) ? properties.getResendCooldown().toSeconds()
                : properties.getResendCooldown().toSeconds();
    }

    // ---------- internals ----------

    /**
     * One committed transaction creates the challenge (superseding any PENDING
     * predecessor under its row lock); delivery runs strictly after that
     * commit; the {@code delivered} flag is recorded in its own transaction.
     * The OTP plaintext lives only in this stack frame and the mail body.
     */
    private boolean issueAndDeliver(User user, String requestIp) {
        Instant now = Instant.now(clock);
        String otp = otpHasher.generateOtp();
        EmailVerificationChallenge created = tx.execute(status -> {
            // Supersede any PENDING predecessor first (flush before insert so
            // the partial unique index never sees two PENDING rows).
            challengeRepository.findPendingForUpdate(user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                    .ifPresent(pending -> {
                        pending.markSuperseded(now);
                        challengeRepository.saveAndFlush(pending);
                    });
            EmailVerificationChallenge challenge = new EmailVerificationChallenge(
                    user, EmailChallengePurpose.ACCOUNT_VERIFICATION,
                    otpHasher.hash(otp), now.plus(properties.getOtpTtl()), requestIp);
            return challengeRepository.saveAndFlush(challenge);
        });

        boolean sent = mailSender.sendAccountVerificationOtp(
                user.getEmail(), user.getDisplayName(), otp,
                properties.getOtpTtl().toSeconds());
        if (sent) {
            created.markDelivered();
            tx.executeWithoutResult(status -> challengeRepository.save(created));
        } else {
            log.warn("OTP mail not delivered for user {} (resend is the recovery flow)", user.getId());
        }
        return sent;
    }

    private void assertEmailRateLimit(String email, Instant now) {
        long issued = challengeRepository.countByUserEmailSince(
                email, now.minus(properties.getEmailWindow()));
        if (issued >= properties.getMaxChallengesPerEmail()) {
            throw new AuthenticationException(AuthErrorCode.AUTH_RATE_LIMITED);
        }
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
