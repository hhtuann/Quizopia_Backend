package com.quizopia.backend.authentication.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Review finding M1-06 regression proof: authenticated, re-verified Google
 * account linking against a SIMULATED OIDC provider (locally-minted RSA ID
 * tokens; the token endpoint and JWKS are test doubles). Live Google linking
 * stays BLOCKED without real credentials.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, GoogleLinkIntegrationTests.LinkTestConfig.class})
@ActiveProfiles("test")
class GoogleLinkIntegrationTests extends AbstractAuthenticationIntegrationTests {

    private static final String ISSUER = "https://fake-issuer.test";
    private static final String CLIENT_ID = "test-google-client-id";
    
    /** Production token exchange is replaced by a mutable holder. */
    @Autowired
    private AtomicReference<String> nextIdToken;

    @Autowired
    private AtomicReference<Runnable> exchangeHook;

    @Autowired
    private GoogleLinkService googleLinkService;

    @Autowired
    private OauthIdentityService identityService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class LinkTestConfig {

        /**
         * ONE simulated provider signing key: the tests sign ID tokens with it
         * and the validator verifies against its public half. Both beans must
         * share the same material or every signature check fails closed.
         */
        @Bean
        RSAKey linkTestSigningKey() throws Exception {
            return new RSAKeyGenerator(2048)
                    .keyID("link-test-key").keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).generate();
        }

        @Bean
        @org.springframework.context.annotation.Primary
        JWKSource<SecurityContext> testJwkSource(RSAKey linkTestSigningKey) {
            return new ImmutableJWKSet<>(new JWKSet(linkTestSigningKey.toPublicJWK()));
        }

        @Bean
        @org.springframework.context.annotation.Primary
        TokenExchangeClient testTokenExchangeClient(AtomicReference<String> nextIdToken,
                AtomicReference<Runnable> exchangeHook) {
            return (code, verifier, redirectUri) -> {
                Runnable hook = exchangeHook.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
                String token = nextIdToken.get();
                if (token == null) {
                    throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
                }
                return token;
            };
        }

        @Bean
        AtomicReference<String> nextIdToken() {
            return new AtomicReference<>();
        }

        @Bean
        AtomicReference<Runnable> exchangeHook() {
            return new AtomicReference<>();
        }
    }

    @Autowired
    private RSAKey linkTestSigningKey;

    @Autowired
    private OAuthProperties oauthProperties;

    @Test
    void emailChangeDuringProviderExchangeRejectsOriginalAddressProof() throws Exception {
        User user = verifiedStudent("address" + UUID.randomUUID().toString().substring(0, 8));
        Started started = startLinkFor(user, "Passw0rd!");
        armIdToken(started.nonce(), user.getEmail());
        exchangeHook.set(() -> jdbc.update(
                "update users set email = ?, email_verified_at = null where id = ?",
                UUID.randomUUID() + "@example.com", user.getId()));
        AuthenticationException error = catchThrowableOfType(
                () -> googleLinkService.completeLink("provider-code", started.state()), AuthenticationException.class);
        assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getEmailVerifiedAt()).isNull();
    }

    @Test
    void credentialChangeDuringProviderExchangeRejectsOriginalLinkProof() throws Exception {
        User user = verifiedStudent("epoch" + UUID.randomUUID().toString().substring(0, 8));
        Started started = startLinkFor(user, "Passw0rd!");
        armIdToken(started.nonce(), user.getEmail());
        exchangeHook.set(() -> jdbc.update(
                "update users set token_version = token_version + 1 where id = ?", user.getId()));
        AuthenticationException error = catchThrowableOfType(
                () -> googleLinkService.completeLink("provider-code", started.state()), AuthenticationException.class);
        assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
    }

    /** Registers + verifies a student over HTTP; returns the logged-in user. */
    private User verifiedStudent(String username) throws Exception {
        String email = username + "@example.com";
        registerAndVerify(username, email, "Passw0rd!");
        login(username, "Passw0rd!");
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private record Started(String state, String nonce) {
    }

    private Started startLinkFor(User user, String password) {
        String url = googleLinkService.startLink(user, password);
        String state = queryParam(url, "state");
        String nonce = queryParam(url, "nonce");
        return new Started(state, nonce);
    }

    /** Mints a provider ID token; returns the subject it was minted for. */
    private String armIdToken(String nonce, String email) throws Exception {
        return armIdToken(nonce, email, googleSub());
    }

    private String armIdToken(String nonce, String email, String subject) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(oauthProperties.getGoogle().getIssuer())
                .subject(subject)
                .audience(oauthProperties.getGoogle().getClientId())
                .claim("email", email)
                .claim("email_verified", true)
                .claim("nonce", nonce)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(linkTestSigningKey.getKeyID()).build(),
                claims);
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(linkTestSigningKey));
        nextIdToken.set(jwt.serialize());
        return claims.getSubject();
    }

    @Test
    void linkingBindsGoogleIdentityAndProvesEmail() throws Exception {
        User user = verifiedStudent("link-ok-" + UUID.randomUUID().toString().substring(0, 6));
        Started started = startLinkFor(user, "Passw0rd!");
        String armedSub = armIdToken(started.nonce(), user.getEmail());

        User linked = googleLinkService.completeLink("auth-code", started.state());

        assertThat(linked.getId()).isEqualTo(user.getId());
        User fresh = userRepository.findById(user.getId()).orElseThrow();
        assertThat(fresh.getEmailVerifiedAt())
                .as("a completed link is a real email-ownership proof")
                .isNotNull();
        // Subsequent Google LOGIN (same iss+sub) resolves the SAME user id.
        ValidatedOidcIdentity identity = new ValidatedOidcIdentity(
                oauthProperties.getGoogle().getIssuer(), armedSub, user.getEmail(), true);
        assertThat(identityService.resolveGoogleUser(identity).getId()).isEqualTo(user.getId());
    }

    @Test
    void wrongPasswordCannotStartLink() throws Exception {
        User user = verifiedStudent("link-badpw-" + UUID.randomUUID().toString().substring(0, 6));
        AuthenticationException ex = catchThrowableOfType(
                () -> googleLinkService.startLink(user, "TotallyWrong1!"),
                AuthenticationException.class);
        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void stateIsSingleUse() throws Exception {
        User user = verifiedStudent("link-reuse-" + UUID.randomUUID().toString().substring(0, 6));
        Started started = startLinkFor(user, "Passw0rd!");
        armIdToken(started.nonce(), user.getEmail());
        assertThat(googleLinkService.completeLink("code-1", started.state()).getId())
                .isEqualTo(user.getId());

        AuthenticationException second = catchThrowableOfType(
                () -> googleLinkService.completeLink("code-2", started.state()),
                AuthenticationException.class);
        assertThat(second.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
    }

    @Test
    void expiredLinkRequestIsRejected() throws Exception {
        User user = verifiedStudent("link-exp-" + UUID.randomUUID().toString().substring(0, 6));
        Started started = startLinkFor(user, "Passw0rd!");
        armIdToken(started.nonce(), user.getEmail());

        jdbc.update("UPDATE oauth_link_requests SET created_at = now() - interval '2 minutes',"
                + " expires_at = now() - interval '1 minute'");

        AuthenticationException ex = catchThrowableOfType(
                () -> googleLinkService.completeLink("code", started.state()),
                AuthenticationException.class);
        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
    }

    @Test
    void nonceMismatchIsRejected() throws Exception {
        User user = verifiedStudent("link-nonce-" + UUID.randomUUID().toString().substring(0, 6));
        Started started = startLinkFor(user, "Passw0rd!");
        armIdToken("different-nonce", user.getEmail());

        AuthenticationException ex = catchThrowableOfType(
                () -> googleLinkService.completeLink("code", started.state()),
                AuthenticationException.class);
        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_STATE_INVALID);
        assertThat(countFor(user.getId())).isZero();
    }

    @Test
    void googleEmailMustMatchAccountEmail() throws Exception {
        User user = verifiedStudent("link-mismatch-" + UUID.randomUUID().toString().substring(0, 6));
        Started started = startLinkFor(user, "Passw0rd!");
        armIdToken(started.nonce(), "someone-else@example.com");

        AuthenticationException ex = catchThrowableOfType(
                () -> googleLinkService.completeLink("code", started.state()),
                AuthenticationException.class);
        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
    }

    @Test
    void identityCannotBeLinkedToTwoUsers() throws Exception {
        User owner = verifiedStudent("link-a-" + UUID.randomUUID().toString().substring(0, 6));
        Started started = startLinkFor(owner, "Passw0rd!");
        String sharedSub = armIdToken(started.nonce(), owner.getEmail());
        assertThat(googleLinkService.completeLink("code", started.state()).getId())
                .isEqualTo(owner.getId());

        // A second user re-authenticates and tries to claim the SAME Google
        // identity (same iss+sub, their own email): rejected.
        User other = verifiedStudent("link-b-" + UUID.randomUUID().toString().substring(0, 6));
        Started second = startLinkFor(other, "Passw0rd!");
        armIdToken(second.nonce(), other.getEmail(), sharedSub);
        AuthenticationException ex = catchThrowableOfType(
                () -> googleLinkService.completeLink("code", second.state()),
                AuthenticationException.class);
        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
        assertThat(countFor(owner.getId()) + countFor(other.getId())).isEqualTo(1);
        assertThat(countFor(other.getId())).isZero();
    }

    @Test
    void concurrentCompletionsConsumeExactlyOnce() throws Exception {
        User user = verifiedStudent("link-conc-" + UUID.randomUUID().toString().substring(0, 6));
        Started started = startLinkFor(user, "Passw0rd!");
        armIdToken(started.nonce(), user.getEmail());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<User> call = () -> googleLinkService.completeLink("code", started.state());
        Future<User> a = pool.submit(call);
        Future<User> b = pool.submit(call);
        int successes = 0;
        try {
            a.get();
            successes++;
        } catch (Exception ignored) {
            // rejected: single-use state
        }
        try {
            b.get();
            successes++;
        } catch (Exception ignored) {
            // rejected: single-use state
        }
        pool.shutdownNow();

        assertThat(successes).isEqualTo(1);
        assertThat(countFor(user.getId())).isEqualTo(1);
    }

    /** Unique Google subject per test — identities must not leak across tests. */
    private static String googleSub() {
        return "google-link-sub-" + UUID.randomUUID();
    }

    private int countFor(Long userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM external_identities WHERE user_id = ?",
                Integer.class, userId);
    }

    @Test
    void repeatedWrongLinkPasswordsLockTheDurableAccount() throws Exception {
        User user = verifiedStudent("link-lock-" + UUID.randomUUID().toString().substring(0, 6));
        // Wrong re-authentications: durable counter increments each time and
        // the lock engages AT the threshold (5, matching login policy).
        for (int i = 1; i <= 5; i++) {
            final String wrong = "WrongPass" + i + "!";
            AuthenticationException ex = catchThrowableOfType(
                    () -> googleLinkService.startLink(user, wrong),
                    AuthenticationException.class);
            assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
            assertThat(jdbc.queryForObject(
                    "SELECT failed_login_attempts FROM users WHERE id = ?",
                    Integer.class, user.getId())).isEqualTo(i);
        }
        // Next attempt — even with the CORRECT password — is locked (423)
        // and the lockout is committed for real (independent of any session).
        AuthenticationException locked = catchThrowableOfType(
                () -> googleLinkService.startLink(user, "Passw0rd!"),
                AuthenticationException.class);
        assertThat(locked.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
        assertThat(jdbc.queryForObject(
                "SELECT locked_until FROM users WHERE id = ?", java.sql.Timestamp.class,
                user.getId())).isNotNull();
    }

    @Test
    void concurrentWrongLinkPasswordsCannotBypassTheLockout() throws Exception {
        User user = verifiedStudent("link-conc-" + UUID.randomUUID().toString().substring(0, 6));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<AuthErrorCode>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String wrong = "Wrong" + i + "Password!";
            calls.add(() -> catchThrowableOfType(
                    () -> googleLinkService.startLink(user, wrong),
                    AuthenticationException.class).getErrorCode());
        }
        List<Future<AuthErrorCode>> futures = new ArrayList<>();
        calls.forEach(c -> futures.add(pool.submit(c)));
        Set<AuthErrorCode> codes = new java.util.HashSet<>();
        for (Future<AuthErrorCode> f : futures) {
            codes.add(f.get());
        }
        pool.shutdownNow();

        // Every attempt is a credentials failure or a lock rejection — never a
        // success — and the committed counter is at least the threshold.
        assertThat(codes).isSubsetOf(AuthErrorCode.AUTH_INVALID_CREDENTIALS,
                AuthErrorCode.AUTH_ACCOUNT_LOCKED);
        assertThat(jdbc.queryForObject(
                "SELECT failed_login_attempts FROM users WHERE id = ?",
                Integer.class, user.getId())).isGreaterThanOrEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT locked_until FROM users WHERE id = ?", java.sql.Timestamp.class,
                user.getId())).isNotNull();
    }

    @Test
    void correctLinkPasswordClearsTransientLockState() throws Exception {
        User user = verifiedStudent("link-clear-" + UUID.randomUUID().toString().substring(0, 6));
        catchThrowableOfType(() -> googleLinkService.startLink(user, "WrongPass1!"),
                AuthenticationException.class);
        assertThat(jdbc.queryForObject(
                "SELECT failed_login_attempts FROM users WHERE id = ?",
                Integer.class, user.getId())).isEqualTo(1);

        String url = googleLinkService.startLink(user, "Passw0rd!");
        assertThat(url).contains("state=");
        assertThat(jdbc.queryForObject(
                "SELECT failed_login_attempts FROM users WHERE id = ?",
                Integer.class, user.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT locked_until FROM users WHERE id = ?", java.sql.Timestamp.class,
                user.getId())).isNull();
    }

    private static String queryParam(String url, String name) {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("no param " + name + " in " + url);
    }
}
