package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.ExternalIdentity;
import com.quizopia.backend.identity.domain.model.ExternalProvider;
import com.quizopia.backend.identity.domain.model.Role;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
import com.quizopia.backend.identity.repository.ExternalIdentityRepository;
import com.quizopia.backend.identity.repository.RoleRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.security.password.PasswordHasher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * OAuth/OIDC identity sync (ADR-002 §1/§3, review finding M1-05).
 *
 * <h2>Concurrency model</h2>
 * {@code resolveGoogleUser()} is a NON-transactional orchestrator. All state
 * changes run in short {@link TransactionTemplate} transactions, and a
 * constraint violation inside one transaction NEVER leads to more queries on
 * that (aborted) transaction — the winner is re-read in a FRESH transaction.
 * The database uniqueness constraints remain the single source of truth:
 * <ul>
 *   <li>{@code external_identities (provider, issuer, subject)} decides the
 *       user for an identity — concurrent first logins converge on exactly one
 *       user, identity and STUDENT grant;</li>
 *   <li>{@code users lower(email)} enforces the no-takeover policy: a Google
 *       login may never claim an existing password account's address.</li>
 * </ul>
 *
 * <p>Identity rules: the external key is (provider, issuer, subject) and the
 * same key always resolves to the same Quizopia user id. A Google email that
 * collides with an existing password account is REJECTED on the LOGIN flow —
 * linking requires the authenticated, re-verified flow in
 * {@link GoogleLinkService}. New users get a per-account random password hash
 * (unusable, never returned), the STUDENT role, and a verified email (the
 * provider asserted {@code email_verified}).
 */
@Service
public class OauthIdentityService {

    private static final Logger log = LoggerFactory.getLogger(OauthIdentityService.class);
    private static final String STUDENT_ROLE_CODE = "STUDENT";
    /** Validity of the one-time exchange code handed to the SPA. */
    private static final Duration EXCHANGE_CODE_TTL = Duration.ofSeconds(60);
    private static final int PROVISION_ATTEMPTS = 3;

    private final ExternalIdentityRepository externalIdentityRepository;
    private final com.quizopia.backend.identity.repository.OauthLoginCodeRepository oauthLoginCodeRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordHasher passwordHasher;
    private final com.quizopia.backend.security.token.RefreshTokenHasher sha256Hasher;

    private final Clock clock;
    private final TransactionTemplate tx;
    private final SecureRandom secureRandom = new SecureRandom();
    private final com.quizopia.backend.authentication.application.CredentialSecurityService credentialSecurity;

    public OauthIdentityService(ExternalIdentityRepository externalIdentityRepository,
            com.quizopia.backend.identity.repository.OauthLoginCodeRepository oauthLoginCodeRepository,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository,
            PasswordHasher passwordHasher,
            com.quizopia.backend.security.token.RefreshTokenHasher sha256Hasher,

            Clock clock,
            TransactionTemplate tx,
            com.quizopia.backend.authentication.application.CredentialSecurityService credentialSecurity) {
        this.externalIdentityRepository = externalIdentityRepository;
        this.oauthLoginCodeRepository = oauthLoginCodeRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordHasher = passwordHasher;
        this.sha256Hasher = sha256Hasher;

        this.clock = clock;
        this.tx = tx;
        this.credentialSecurity = credentialSecurity;
    }

    /**
     * Resolves the Quizopia user for a validated Google identity, provisioning
     * a new STUDENT account on first login. Concurrent calls for the same
     * identity converge on one user; a transient loser retries.
     */
    public User resolveGoogleUser(ValidatedOidcIdentity identity) {
        for (int attempt = 1; attempt <= PROVISION_ATTEMPTS; attempt++) {
            ExternalIdentity existing = findByIdentity(identity).orElse(null);
            if (existing != null) {
                return recordExistingLogin(existing, identity);
            }
            if (emailExists(identity.email())) {
                // Review R2-M1-A: this read pair (identity absent → email
                // present) is NOT a conflict proof. A concurrent login for
                // the SAME issuer+sub may have committed between the two
                // reads. Recheck the stable identity key BEFORE rejecting;
                // only a genuinely foreign owner of the address conflicts.
                User concurrentWinner = resolveEmailCollisionByIdentity(identity);
                if (concurrentWinner != null) {
                    log.info("Concurrent same-identity Google login resolved to user {}",
                            concurrentWinner.getId());
                    return recordExistingLogin(
                            externalIdentityRepository.findByProviderAndIssuerAndSubject(
                                            ExternalProvider.GOOGLE, identity.issuer(), identity.subject())
                                    .orElseThrow(), identity);
                }
                log.info("Google login rejected: email already belongs to a different account");
                throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
            }
            try {
                User provisioned = tx.execute(status -> provisionNewGoogleUser(identity));
                if (provisioned != null) {
                    return provisioned;
                }
            } catch (AuthenticationException conflict) {
                // Review R3-M1-01 (defensive third window): a conflict thrown
                // from inside the provisioning transaction is re-resolved by
                // the stable identity key in a FRESH transaction before it is
                // allowed to surface.
                User winner = resolveEmailCollisionByIdentity(identity);
                if (winner != null) {
                    log.info("Conflict inside provisioning resolved to same-identity user {}",
                            winner.getId());
                    return recordExistingLogin(findByIdentity(identity).orElseThrow(), identity);
                }
                throw conflict;
            } catch (DataIntegrityViolationException race) {
                // The provision transaction is aborted; NEVER query it again.
                // Resolve the race in a FRESH transaction.
                User winner = resolveEmailCollisionByIdentity(identity);
                if (winner != null) {
                    log.info("Concurrent Google login for subject resolved to user {}", winner.getId());
                    return recordExistingLogin(findByIdentity(identity).orElseThrow(), identity);
                }
                if (emailExists(identity.email())) {
                    // A concurrent password registration claimed the address.
                    throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
                }
                // Transient loser (the winning transaction itself aborted):
                // loop and try again on the next attempt.
                log.debug("Google provisioning race on attempt {} for subject", attempt);
            }
        }
        throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
    }

    /**
     * Review R2-M1-A: resolves an observed email collision by re-reading the
     * STABLE identity key in a fresh transaction. Returns the winner's user
     * when the address was claimed by a concurrent login for the SAME
     * (provider, issuer, subject); returns {@code null} only when the address
     * genuinely belongs to a different subject or a password account, which
     * is the only legitimate conflict. Package-private for deterministic
     * interleaving tests.
     */
    User resolveEmailCollisionByIdentity(ValidatedOidcIdentity identity) {
        return externalIdentityRepository
                .findByProviderAndIssuerAndSubject(
                        ExternalProvider.GOOGLE, identity.issuer(), identity.subject())
                .map(ExternalIdentity::getUser)
                .orElse(null);
    }

    /** Login bookkeeping for an already-linked identity (fresh short tx). */
    private User recordExistingLogin(ExternalIdentity existing, ValidatedOidcIdentity identity) {
        Long userId = existing.getUser().getId();
        return tx.execute(status -> {
            User user = credentialSecurity.lockUser(userId);
            Instant now = Instant.now(clock);
            ExternalIdentity managed = externalIdentityRepository.findById(existing.getId())
                    .orElseThrow();
            assertLoginAllowed(user);
            managed.recordLogin(now);
            externalIdentityRepository.saveAndFlush(managed);
            user.setLastLoginAt(now);
            if (user.getEmailVerifiedAt() == null && identity.emailVerified()
                    && user.getEmail().trim().equalsIgnoreCase(identity.email().trim())) {
                user.setEmailVerifiedAt(now);
            }
            userRepository.saveAndFlush(user);
            return user;
        });
    }

    /** Runs INSIDE a transaction: user + role + identity link. */
    private User provisionNewGoogleUser(ValidatedOidcIdentity identity) {
        Instant now = Instant.now(clock);
        // Idempotence under concurrency: re-check inside the transaction.
        ExternalIdentity existing = findByIdentity(identity).orElse(null);
        if (existing != null) {
            return recordExistingLogin(existing, identity);
        }
        if (emailExists(identity.email())) {
            // Review R3-M1-01: inside THIS transaction the same read-pair
            // race exists — a concurrent same-identity winner may have
            // committed between the findByIdentity above and this email
            // check. Re-read the stable identity key BEFORE conflicting;
            // only a genuinely foreign owner of the address throws.
            ExternalIdentity innerWinner = findByIdentity(identity).orElse(null);
            if (innerWinner != null) {
                return recordExistingLogin(innerWinner, identity);
            }
            throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
        }

        User user = createGoogleUser(identity, now);
        ExternalIdentity link = new ExternalIdentity(
                user, ExternalProvider.GOOGLE, identity.issuer(), identity.subject(),
                identity.email(), identity.emailVerified());
        externalIdentityRepository.saveAndFlush(link);
        log.info("Linked Google identity to new user {}", user.getId());
        return user;
    }

    /**
     * Links a validated Google identity to an EXISTING authenticated user
     * (review M1-06, used by {@link GoogleLinkService}). Returns the user.
     * Fails closed when the identity is already linked to someone else or the
     * Google email does not match the account address.
     */
    public User linkGoogleIdentityToUser(User user, ValidatedOidcIdentity identity) {
        return linkGoogleIdentityToUser(user.getId(), user.getTokenVersion(), identity);
    }

    public User linkGoogleIdentityToUser(Long userId, int proofEpoch, ValidatedOidcIdentity identity) {
        return tx.execute(status -> {
            User managed = credentialSecurity.lockUser(userId);
            credentialSecurity.requireActiveUnlocked(managed);
            if (managed.getTokenVersion() != proofEpoch) {
                throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
            }
            if (!identity.emailVerified()
                    || !managed.getEmail().trim().equalsIgnoreCase(identity.email().trim())) {
                throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
            }
            ExternalIdentity existing = findByIdentity(identity).orElse(null);
            if (existing != null && !existing.getUser().getId().equals(userId)) {
                throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
            }
            if (existing == null) {
                externalIdentityRepository.saveAndFlush(new ExternalIdentity(
                        managed, ExternalProvider.GOOGLE, identity.issuer(), identity.subject(),
                        identity.email(), identity.emailVerified()));
            }
            if (managed.getEmailVerifiedAt() == null) {
                managed.setEmailVerifiedAt(Instant.now(clock));
            }
            return managed;
        });
    }

    /**
     * Mints a single-use opaque login code for the SPA exchange step. The raw
     * value is returned exactly once; only its SHA-256 hash is stored.
     */
    public String mintExchangeCode(User user) {
        return mintExchangeCode(user.getId(), user.getTokenVersion());
    }

    public String mintExchangeCode(Long userId, int proofEpoch) {
        return tx.execute(status -> {
            User locked = credentialSecurity.lockUser(userId);
            credentialSecurity.requireActiveUnlocked(locked);
            if (locked.getTokenVersion() != proofEpoch) {
                throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
            }
            byte[] bytes = new byte[32];
            secureRandom.nextBytes(bytes);
            String rawCode = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            oauthLoginCodeRepository.save(new com.quizopia.backend.identity.domain.model.OauthLoginCode(
                    UUID.randomUUID(), locked, sha256Hasher.hash(rawCode),
                    Instant.now(clock).plus(EXCHANGE_CODE_TTL)));
            return rawCode;
        });
    }

    /**
     * Consumes a one-time exchange code (exactly-once under concurrency) and
     * returns the authenticated user. Expired or already-consumed codes fail
     * closed.
     */
    public User consumeExchangeCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
        String codeHash = sha256Hasher.hash(rawCode.trim());
        User consumed = tx.execute(status -> {
            Long userId = oauthLoginCodeRepository.findUserIdByCodeHash(codeHash)
                    .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID));
            User user = credentialSecurity.lockUser(userId);
            com.quizopia.backend.identity.domain.model.OauthLoginCode code = oauthLoginCodeRepository
                    .findForUpdateByCodeHash(codeHash)
                    .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID));
            Instant now = Instant.now(clock);
            if (code.isConsumed() || code.isExpired(now) || code.getCredentialEpoch() != user.getTokenVersion()) {
                throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
            }
            // Clamp past created_at: the row's creation timestamp may be a few
            // microseconds AFTER the captured instant (generated at insert), and
            // chk_oauth_login_codes_consumed requires consumed_at >= created_at.
            code.markConsumed(laterOf(now, code.getCreatedAt()));
            credentialSecurity.requireActiveUnlocked(user);
            return user;
        });
        return consumed;
    }

    // ---------- internals ----------

    /** Seam for deterministic interleaving tests (review R2-M1-A). */
    protected boolean emailExists(String email) {
        return userRepository.existsByEmailIgnoreCase(email);
    }

    private java.util.Optional<ExternalIdentity> findByIdentity(ValidatedOidcIdentity identity) {
        return externalIdentityRepository.findByProviderAndIssuerAndSubject(
                ExternalProvider.GOOGLE, identity.issuer(), identity.subject());
    }

    private User createGoogleUser(ValidatedOidcIdentity identity, Instant now) {
        String username = uniqueUsernameFor(identity.email());
        // Per-account random credential hash: satisfies NOT NULL without ever
        // being a usable or reusable password (ADR-002 §1).
        byte[] randomPassword = new byte[32];
        secureRandom.nextBytes(randomPassword);
        User user = new User(
                username,
                identity.email(),
                passwordHasher.hash(Base64.getEncoder().encodeToString(randomPassword)),
                username);
        user.setLocalCredentialState(com.quizopia.backend.identity.domain.model.LocalCredentialState.DISABLED);
        user.setEmailVerifiedAt(now);
        // Google asserts email_verified; the account's own login gate follows
        // the provider, not an OTP.
        user.setLoginRequiresEmailVerification(Boolean.FALSE);
        user.setLastLoginAt(now);
        user = userRepository.saveAndFlush(user);

        Role student = roleRepository.findByCode(STUDENT_ROLE_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "Required role '" + STUDENT_ROLE_CODE + "' is not seeded; check Flyway V3"));
        userRoleRepository.insertAssignmentIfAbsent(user.getId(), student.getId());

        return user;
    }

    private String uniqueUsernameFor(String email) {
        String base = email.substring(0, email.indexOf('@')).toLowerCase()
                .replaceAll("[^a-z0-9_]", "");
        if (base.isBlank() || base.length() < 3) {
            base = "google_user";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        if (!userRepository.existsByUsernameIgnoreCase(base)) {
            return base;
        }
        for (int i = 1; i <= 1000; i++) {
            String candidate = base + "_" + i;
            if (!userRepository.existsByUsernameIgnoreCase(candidate)) {
                return candidate;
            }
        }
        // Deterministic probing failed: fall back to randomness.
        return base + "_" + Long.toHexString(secureRandom.nextLong());
    }

    private static void assertLoginAllowed(User user) {
        UserStatus status = user.getStatus();
        if (status == UserStatus.DISABLED) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (status == UserStatus.PENDING) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_PENDING);
        }
    }

    private static Instant laterOf(Instant a, Instant b) {
        return b != null && b.isAfter(a) ? b : a;
    }
}
