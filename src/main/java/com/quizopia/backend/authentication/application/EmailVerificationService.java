package com.quizopia.backend.authentication.application;

import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.EmailChallengePurpose;
import com.quizopia.backend.identity.domain.model.EmailVerificationChallenge;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.EmailVerificationChallengeRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.notification.email.OtpMailSender;
import com.quizopia.backend.security.otp.OtpHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Email-verification OTP lifecycle (ADR-002 §2): issue, verify, resend.
 *
 * <p>Security properties: OTP stored only as HMAC-SHA256; constant-time
 * compare; one PENDING challenge per account (resend supersedes); per-challenge
 * failed-attempt cap; per-email and per-IP issuance rate limits; idempotent
 * verification (row-locked CONSUMED transition, already-verified is a no-op
 * success). Unknown emails are answered like known ones so the public resend
 * endpoint cannot enumerate accounts. The OTP plaintext exists only in the
 * outgoing mail — never logged, never stored, never returned by the API.
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

    public EmailVerificationService(EmailVerificationChallengeRepository challengeRepository,
            UserRepository userRepository,
            OtpHasher otpHasher,
            OtpMailSender mailSender,
            EmailVerificationProperties properties,
            Clock clock) {
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.otpHasher = otpHasher;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates the initial ACCOUNT_VERIFICATION challenge for a just-registered
     * user and attempts delivery. Called inside the registration transaction;
     * a delivery failure never fails registration — the resend endpoint is the
     * recovery flow.
     *
     * @return {@code true} when the OTP mail was delivered
     */
    @Transactional
    public boolean issueForNewRegistration(User user, String requestIp) {
        // A brand-new account cannot hit the per-email limit from earlier
        // challenges of the same address (the address was free), but the IP
        // limit applies to the registration endpoint too.
        assertIpRateLimit(requestIp);
        EmailVerificationChallenge challenge = createChallenge(user, requestIp);
        return challenge.isDelivered();
    }

    /**
     * Verifies the OTP for the given email. Idempotent: an already-verified
     * account returns success without touching any challenge. Concurrent
     * submissions of the same OTP are serialized by the challenge row lock —
     * exactly one CONSUMED transition.
     */
    @Transactional
    public void verify(String email, String otp) {
        Instant now = Instant.now(clock);
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email == null ? "" : email.trim());
        if (userOpt.isEmpty()) {
            // Anti-enumeration: unknown email looks like a wrong OTP.
            throw new AuthenticationException(AuthErrorCode.AUTH_OTP_INVALID);
        }
        User user = userOpt.get();

        if (user.getEmailVerifiedAt() != null) {
            return; // already verified: idempotent success
        }

        EmailVerificationChallenge challenge = challengeRepository
                .findPendingForUpdate(user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_OTP_NOT_FOUND));

        if (challenge.isExpired(now)) {
            // The row stays PENDING-but-expired; only resend can recover.
            throw new AuthenticationException(AuthErrorCode.AUTH_OTP_EXPIRED);
        }
        if (challenge.getFailedAttempts() >= properties.getMaxFailedAttempts()) {
            markFailedLocked(challenge, now);
        }
        if (!otpHasher.matches(normalizeOtp(otp), challenge.getOtpHash())) {
            challenge.incrementFailedAttempts();
            if (challenge.getFailedAttempts() >= properties.getMaxFailedAttempts()) {
                challenge.markFailed(now);
            }
            challengeRepository.saveAndFlush(challenge);
            if (challenge.getStatus() == com.quizopia.backend.identity.domain.model.EmailChallengeStatus.FAILED) {
                throw new AuthenticationException(AuthErrorCode.AUTH_OTP_MAX_ATTEMPTS);
            }
            throw new AuthenticationException(AuthErrorCode.AUTH_OTP_INVALID);
        }

        challenge.markConsumed(now);
        challengeRepository.saveAndFlush(challenge);
        user.setEmailVerifiedAt(now);
        userRepository.saveAndFlush(user);
        log.info("Email verified for user {}", user.getId());
    }

    /**
     * Re-issues the ACCOUNT_VERIFICATION OTP: supersedes the previous
     * challenge, enforces the per-account cooldown plus per-email/per-IP rate
     * limits, then attempts delivery. Unknown emails answer like successes so
     * the endpoint cannot be used to enumerate accounts.
     *
     * @return seconds the client should wait before the next resend
     */
    @Transactional
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

        // Supersede under the row lock AND flush before inserting the new
        // PENDING row: Hibernate orders inserts before updates, so without
        // this flush both rows would be PENDING at insert time and violate
        // the partial unique index.
        EmailVerificationChallenge superseded = challengeRepository
                .findPendingForUpdate(user.getId(), EmailChallengePurpose.ACCOUNT_VERIFICATION)
                .orElse(null);
        if (superseded != null) {
            superseded.markSuperseded(now);
            challengeRepository.saveAndFlush(superseded);
        }

        EmailVerificationChallenge challenge = createChallenge(user, requestIp);
        return properties.getResendCooldown().toSeconds();
    }

    // ---------- internals ----------

    /**
     * Persists a fresh hashed challenge, then hands the in-memory plaintext
     * OTP to the mail transport. The plaintext never survives this method.
     */
    private EmailVerificationChallenge createChallenge(User user, String requestIp) {
        Instant now = Instant.now(clock);
        String otp = otpHasher.generateOtp();
        EmailVerificationChallenge challenge = new EmailVerificationChallenge(
                user, EmailChallengePurpose.ACCOUNT_VERIFICATION,
                otpHasher.hash(otp), now.plus(properties.getOtpTtl()), requestIp);
        challenge = challengeRepository.saveAndFlush(challenge);
        deliver(challenge, otp);
        return challenge;
    }

    private void deliver(EmailVerificationChallenge challenge, String otp) {
        boolean sent = mailSender.sendAccountVerificationOtp(
                challenge.getUser().getEmail(),
                challenge.getUser().getDisplayName(),
                otp,
                properties.getOtpTtl().toSeconds());
        if (sent) {
            challenge.markDelivered();
            challengeRepository.save(challenge);
        } else {
            log.warn("OTP mail not delivered for user {} (resend is the recovery flow)",
                    challenge.getUser().getId());
        }
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

    private void markFailedLocked(EmailVerificationChallenge challenge, Instant now) {
        challenge.markFailed(now);
        challengeRepository.saveAndFlush(challenge);
        throw new AuthenticationException(AuthErrorCode.AUTH_OTP_MAX_ATTEMPTS);
    }

    private static String normalizeOtp(String otp) {
        if (otp == null) {
            return "";
        }
        return otp.trim();
    }
}
