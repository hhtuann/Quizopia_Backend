package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.ExternalIdentity;
import com.quizopia.backend.identity.domain.model.ExternalProvider;
import com.quizopia.backend.identity.domain.model.OauthLoginCode;
import com.quizopia.backend.identity.domain.model.Role;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserRole;
import com.quizopia.backend.identity.domain.model.UserStatus;
import com.quizopia.backend.identity.repository.ExternalIdentityRepository;
import com.quizopia.backend.identity.repository.OauthLoginCodeRepository;
import com.quizopia.backend.identity.repository.RoleRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.security.password.PasswordHasher;
import com.quizopia.backend.security.token.RefreshTokenHasher;
import com.quizopia.backend.workspace.application.WorkspaceProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * OAuth/OIDC identity sync (ADR-002 §1/§3).
 *
 * <p>Rules: the external identity key is (provider, issuer, subject) and the
 * same key always resolves to the same Quizopia user id. A Google email that
 * collides with an existing password account is REJECTED — never auto-linked;
 * linking requires an authenticated flow (Profile feature, post-M1). New
 * users get a per-account random password hash (unusable, never returned),
 * the STUDENT role, a personal workspace, and a verified email (the provider
 * already asserted {@code email_verified}). Duplicate provisioning under
 * concurrent callbacks is settled by the unique index: the loser re-reads the
 * winner's row.
 */
@Service
public class OauthIdentityService {

    private static final Logger log = LoggerFactory.getLogger(OauthIdentityService.class);
    private static final String STUDENT_ROLE_CODE = "STUDENT";
    /** Validity of the one-time exchange code handed to the SPA. */
    private static final Duration EXCHANGE_CODE_TTL = Duration.ofSeconds(60);

    private final ExternalIdentityRepository externalIdentityRepository;
    private final OauthLoginCodeRepository oauthLoginCodeRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordHasher passwordHasher;
    private final RefreshTokenHasher sha256Hasher;
    private final WorkspaceProvisioningService workspaceProvisioningService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public OauthIdentityService(ExternalIdentityRepository externalIdentityRepository,
            OauthLoginCodeRepository oauthLoginCodeRepository,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository,
            PasswordHasher passwordHasher,
            RefreshTokenHasher sha256Hasher,
            WorkspaceProvisioningService workspaceProvisioningService,
            Clock clock) {
        this.externalIdentityRepository = externalIdentityRepository;
        this.oauthLoginCodeRepository = oauthLoginCodeRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordHasher = passwordHasher;
        this.sha256Hasher = sha256Hasher;
        this.workspaceProvisioningService = workspaceProvisioningService;
        this.clock = clock;
    }

    /**
     * Resolves the Quizopia user for a validated Google identity, provisioning
     * a new STUDENT account on first login.
     */
    @Transactional
    public User resolveGoogleUser(ValidatedOidcIdentity identity) {
        Instant now = Instant.now(clock);

        ExternalIdentity existing = externalIdentityRepository
                .findByProviderAndIssuerAndSubject(
                        ExternalProvider.GOOGLE, identity.issuer(), identity.subject())
                .orElse(null);
        if (existing != null) {
            User user = existing.getUser();
            assertLoginAllowed(user);
            existing.recordLogin(now);
            user.setLastLoginAt(now);
            userRepository.save(user);
            // Re-flag verified email: the provider re-asserted it this login.
            if (user.getEmailVerifiedAt() == null) {
                user.setEmailVerifiedAt(now);
                userRepository.save(user);
            }
            return user;
        }

        // No prior link: an existing password account with the same email must
        // NOT be silently taken over. Reject; linking ships with Profile.
        if (userRepository.existsByEmailIgnoreCase(identity.email())) {
            log.info("Google login rejected: email already belongs to a password account");
            throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
        }

        User user = createGoogleUser(identity, now);
        ExternalIdentity link = new ExternalIdentity(
                user, ExternalProvider.GOOGLE, identity.issuer(), identity.subject(),
                identity.email(), identity.emailVerified());
        try {
            externalIdentityRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException race) {
            // Concurrent callback won the unique (provider, issuer, subject):
            // adopt the winner's mapping instead of creating a duplicate user.
            ExternalIdentity winner = externalIdentityRepository
                    .findByProviderAndIssuerAndSubject(
                            ExternalProvider.GOOGLE, identity.issuer(), identity.subject())
                    .orElseThrow(() -> race);
            log.info("Concurrent Google login for subject resolved to user {}", winner.getUser().getId());
            return winner.getUser();
        }
        log.info("Linked Google identity to new user {}", user.getId());
        return user;
    }

    /**
     * Mints a single-use opaque login code for the SPA exchange step. The raw
     * value is returned exactly once; only its SHA-256 hash is stored.
     */
    @Transactional
    public String mintExchangeCode(User user) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawCode = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OauthLoginCode code = new OauthLoginCode(
                UUID.randomUUID(), user, sha256Hasher.hash(rawCode),
                Instant.now(clock).plus(EXCHANGE_CODE_TTL));
        oauthLoginCodeRepository.save(code);
        return rawCode;
    }


    private static Instant laterOf(Instant a, Instant b) {
        return b != null && b.isAfter(a) ? b : a;
    }

    /**
     * Consumes a one-time exchange code (exactly-once under concurrency) and
     * returns the authenticated user. Expired or already-consumed codes fail
     * closed.
     */
    @Transactional
    public User consumeExchangeCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
        Instant now = Instant.now(clock);
        OauthLoginCode code = oauthLoginCodeRepository
                .findForUpdateByCodeHash(sha256Hasher.hash(rawCode.trim()))
                .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID));
        if (code.isConsumed() || code.isExpired(now)) {
            throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
        // Clamp past created_at: the row's creation timestamp may be a few
        // microseconds AFTER the captured instant (generated at insert), and
        // chk_oauth_login_codes_consumed requires consumed_at >= created_at.
        code.markConsumed(laterOf(now, code.getCreatedAt()));
        User user = code.getUser();
        assertLoginAllowed(user);
        return user;
    }

    // ---------- internals ----------

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
        user.setEmailVerifiedAt(now);
        user.setLastLoginAt(now);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException race) {
            // Extremely unlikely (unique username/email raced); map to the
            // email conflict so no duplicate account exists afterwards.
            throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
        }

        Role student = roleRepository.findByCode(STUDENT_ROLE_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "Required role '" + STUDENT_ROLE_CODE + "' is not seeded; check Flyway V3"));
        try {
            userRoleRepository.saveAndFlush(new UserRole(user, student, null, null));
        } catch (DataIntegrityViolationException race) {
            // Concurrent insert of the same assignment: PK dedup, adopt it.
            log.debug("Student role for user {} already present", user.getId());
        }
        workspaceProvisioningService.ensurePersonalWorkspace(user);
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
}
