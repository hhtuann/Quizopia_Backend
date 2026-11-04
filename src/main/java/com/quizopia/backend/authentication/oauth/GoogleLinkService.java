package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.OauthLinkRequest;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.OauthLinkRequestRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.security.password.PasswordHasher;
import com.quizopia.backend.security.token.RefreshTokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Authenticated Google account linking (review finding M1-06).
 *
 * <h2>Security contract</h2>
 * <ul>
 *   <li>The caller must ALREADY be signed in to the Quizopia account (JWT) and
 *       must re-authenticate with the account password before a link request
 *       is created — Google-side login alone can never attach an identity.</li>
 *   <li>The authorization request carries state (server-side, SHA-256-stored,
 *       single-use, 5-minute TTL), PKCE (S256) and a nonce, binding the
 *       callback to exactly this account and request.</li>
 *   <li>The callback validates the ID token (signature/issuer/audience/
 *       expiry/nonce/email_verified via {@link GoogleIdTokenValidator}) and
 *       only then links, requiring the Google email to EQUAL the account
 *       address — so a completed link is also a real ownership proof for the
 *       account's email (never the legacy compatibility flag).</li>
 *   <li>One external identity maps to exactly one user (DB unique key);
 *       concurrent completions converge; a mismatched state/nonce/expiry all
 *       fail closed.</li>
 * </ul>
 */
@Service
public class GoogleLinkService {

    private static final Logger log = LoggerFactory.getLogger(GoogleLinkService.class);
    static final Duration LINK_REQUEST_TTL = Duration.ofMinutes(5);
    static final String LINK_CALLBACK_PATH = "/api/auth/oauth/google/link/callback";

    private final OauthLinkRequestRepository linkRequestRepository;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final RefreshTokenHasher sha256Hasher;
    private final GoogleIdTokenValidator tokenValidator;
    private final TokenExchangeClient tokenExchangeClient;
    private final OauthIdentityService identityService;
    private final OAuthProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final SecureRandom secureRandom = new SecureRandom();

    public GoogleLinkService(OauthLinkRequestRepository linkRequestRepository,
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            RefreshTokenHasher sha256Hasher,
            GoogleIdTokenValidator tokenValidator,
            TokenExchangeClient tokenExchangeClient,
            OauthIdentityService identityService,
            OAuthProperties properties,
            Clock clock,
            TransactionTemplate tx) {
        this.linkRequestRepository = linkRequestRepository;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.sha256Hasher = sha256Hasher;
        this.tokenValidator = tokenValidator;
        this.tokenExchangeClient = tokenExchangeClient;
        this.identityService = identityService;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * Starts a link flow after password re-authentication.
     *
     * @return the browser-facing Google authorization URL
     */
    public String startLink(User user, String password) {
        if (!passwordHasher.matches(password == null ? "" : password, user.getPasswordHash())) {
            throw new AuthenticationException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        String state = randomToken(32);
        String codeVerifier = randomToken(64);
        String nonce = randomToken(24);
        Instant now = Instant.now(clock);
        OauthLinkRequest request = tx.execute(status -> linkRequestRepository.saveAndFlush(
                new OauthLinkRequest(UUID.randomUUID(), user, sha256Hasher.hash(state),
                        codeVerifier, nonce, now.plus(LINK_REQUEST_TTL))));
        log.info("Google link flow started for user {}", user.getId());

        OAuthProperties.Google google = properties.getGoogle();
        String redirectUri = google.getRedirectBaseUri() + LINK_CALLBACK_PATH;
        return OAuth2ClientConfig.DEFAULT_AUTHORIZATION_URI
                + "?client_id=" + enc(google.getClientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state)
                + "&nonce=" + enc(nonce)
                + "&code_challenge=" + enc(s256(codeVerifier))
                + "&code_challenge_method=S256";
    }

    /**
     * Completes a link flow: consumes the single-use request bound to the
     * state, exchanges the code (PKCE), validates the ID token against the
     * stored nonce, and links the identity to that request's user.
     *
     * @return the linked Quizopia user
     */
    public User completeLink(String code, String state) {
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
        String stateHash = sha256Hasher.hash(state.trim());

        // Consume the request in its own committed transaction (row lock ⇒
        // exactly-once even for concurrent callbacks with the same state).
        OauthLinkRequest request = tx.execute(status -> {
            OauthLinkRequest locked = linkRequestRepository.findForUpdateByStateHash(stateHash)
                    .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID));
            Instant now = Instant.now(clock);
            if (locked.isConsumed() || locked.isExpired(now)) {
                throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
            }
            locked.markConsumed(laterOf(now, locked.getCreatedAt()));
            linkRequestRepository.saveAndFlush(locked);
            return locked;
        });

        User user = userRepository.findById(request.getUser().getId())
                .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID));

        String redirectUri = properties.getGoogle().getRedirectBaseUri() + LINK_CALLBACK_PATH;
        String idToken = tokenExchangeClient.exchangeForIdToken(code.trim(),
                request.getCodeVerifier(), redirectUri);
        ValidatedOidcIdentity identity = tokenValidator.validate(idToken, request.getNonce());

        User linked = identityService.linkGoogleIdentityToUser(user, identity);
        log.info("Google identity linked to user {}", linked.getId());
        return linked;
    }

    // ---------- helpers ----------

    /** Browser URL of the SPA link-result page (origin of the configured redirect URL). */
    public String linkResultUrl(String status, String errorCode) {
        String origin;
        try {
            origin = new java.net.URL(properties.getFrontendRedirectUrl()).toURI().resolve("/").toString();
            if (origin.endsWith("/")) {
                origin = origin.substring(0, origin.length() - 1);
            }
        } catch (Exception ex) {
            origin = properties.getFrontendRedirectUrl();
        }
        return origin + "/auth/link/callback?status=" + enc(status)
                + (errorCode == null ? "" : "&error=" + enc(errorCode));
    }


    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        secureRandom.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static Instant laterOf(Instant a, Instant b) {
        return b != null && b.isAfter(a) ? b : a;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
