package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.authentication.application.SessionIssuanceService;
import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.repository.ExternalIdentityRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Identity-M1 integration tests for the OAuth identity mapping (ADR-002 §3)
 * against a real PostgreSQL: new-identity provisioning (STUDENT + workspace +
 * verified email), stable issuer+subject → user resolution, email-collision
 * rejection (no auto-linking), and the one-time exchange-code semantics.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
@Transactional
class OauthIdentityIntegrationTests {

    @Autowired
    private OauthIdentityService identityService;

    @Autowired
    private ExternalIdentityRepository externalIdentityRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private SessionIssuanceService sessionIssuanceService;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void firstLoginProvisionsStudentWithWorkspaceAndVerifiedEmail() {
        ValidatedOidcIdentity identity = identity();

        com.quizopia.backend.identity.domain.model.User user = identityService.resolveGoogleUser(identity);

        assertThat(user.getId()).isNotNull();
        assertThat(user.getEmailVerifiedAt()).isNotNull();
        assertThat(user.getUsername()).isEqualTo(expectedUsernameOf(identity.email()));
        assertThat(userRoleRepository.findActiveRoleCodesByUserId(user.getId(), Instant.now()))
                .containsExactly("STUDENT");
        assertThat(workspaceRepository.findByOwnerUserIdAndWorkspaceType(
                user.getId(),
                com.quizopia.backend.workspace.domain.model.WorkspaceType.PERSONAL))
                .isPresent();
        // Google-only accounts must never authenticate with a usable password.
        assertThat(user.getPasswordHash()).startsWith("$argon2");
    }

    @Test
    void sameIssuerAndSubjectResolvesToSameUser() {
        ValidatedOidcIdentity identity = identity();

        Long firstId = identityService.resolveGoogleUser(identity).getId();
        Long secondId = identityService.resolveGoogleUser(identity).getId();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(externalIdentityRepository
                .findByProviderAndIssuerAndSubject(
                        com.quizopia.backend.identity.domain.model.ExternalProvider.GOOGLE,
                        identity.issuer(), identity.subject()))
                .isPresent();
        assertThat(userRepository.findByUsernameIgnoreCase(expectedUsernameOf(identity.email())))
                .isPresent();
    }

    @Test
    void differentSubjectWithSameEmailIsAConflict() {
        ValidatedOidcIdentity identity = identity();
        identityService.resolveGoogleUser(identity);

        // A second Google identity (different sub) claiming the SAME email must
        // not take over or link the existing account.
        ValidatedOidcIdentity impostor = new ValidatedOidcIdentity(
                identity.issuer(), "sub-" + UUID.randomUUID(), identity.email(), true);
        AuthenticationException ex = catchThrowableOfType(
                () -> identityService.resolveGoogleUser(impostor), AuthenticationException.class);
        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
    }

    @Test
    void existingPasswordAccountEmailIsNeverAutoLinked() {
        ValidatedOidcIdentity identity = identity();
        // A pre-existing password account with the same email…
        com.quizopia.backend.identity.domain.model.User user = userRepository.saveAndFlush(
                new com.quizopia.backend.identity.domain.model.User(
                        "pwduser", identity.email(), "$argon2id$stub", "Pwd User"));

        AuthenticationException ex = catchThrowableOfType(
                () -> identityService.resolveGoogleUser(identity), AuthenticationException.class);
        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_EMAIL_CONFLICT);
        // …and no external identity row was created FOR THIS ACCOUNT (other
        // classes in the shared context may have committed their own).
        assertThat(externalIdentityRepository.existsByUserIdAndProvider(
                user.getId(), com.quizopia.backend.identity.domain.model.ExternalProvider.GOOGLE))
                .isFalse();
    }

    @Test
    void exchangeCodeIsSingleUse() {
        com.quizopia.backend.identity.domain.model.User user =
                identityService.resolveGoogleUser(identity());

        String code = identityService.mintExchangeCode(user);
        Long userId = identityService.consumeExchangeCode(code).getId();
        assertThat(userId).isEqualTo(user.getId());

        // Second use fails closed.
        AuthenticationException reuse = catchThrowableOfType(
                () -> identityService.consumeExchangeCode(code), AuthenticationException.class);
        assertThat(reuse.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);

        // Bogus codes never authenticate.
        AuthenticationException bogus = catchThrowableOfType(
                () -> identityService.consumeExchangeCode("bogus-code"), AuthenticationException.class);
        assertThat(bogus.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
    }

    @Test
    void expiredExchangeCodeIsRejected() {
        com.quizopia.backend.identity.domain.model.User user =
                identityService.resolveGoogleUser(identity());
        String code = identityService.mintExchangeCode(user);

        // Backdate the whole row (expires_at must stay > created_at for the
        // check constraint, so both move into the past together).
        entityManager.createNativeQuery(
                        "UPDATE oauth_login_codes" +
                        " SET created_at = now() - interval '2 minutes'," +
                        "     expires_at = now() - interval '1 minute'")
                .executeUpdate();
        // The native update bypasses the persistence context; drop the cached
        // entity so consumeExchangeCode sees the backdated row.
        entityManager.clear();

        AuthenticationException ex = catchThrowableOfType(
                () -> identityService.consumeExchangeCode(code), AuthenticationException.class);
        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
    }

    @Test
    void exchangeIssuesAWorkingQuizopiaSession() {
        com.quizopia.backend.identity.domain.model.User user =
                identityService.resolveGoogleUser(identity());
        String code = identityService.mintExchangeCode(user);

        SessionIssuanceService.IssuedSession session = sessionIssuanceService.issue(
                identityService.consumeExchangeCode(code),
                new ClientContext("oauth-test", "127.0.0.1"));

        assertThat(session.tokenResponse().accessToken()).isNotBlank();
        assertThat(session.tokenResponse().roles()).containsExactly("STUDENT");
        assertThat(session.rawRefreshToken()).isNotBlank();
    }

    // ---------- helpers ----------

    private static ValidatedOidcIdentity identity() {
        return new ValidatedOidcIdentity(
                "https://fake-issuer.test",
                "sub-" + UUID.randomUUID(),
                "learner" + UUID.randomUUID().toString().substring(0, 6) + "@example.com",
                true);
    }

    /** Username generation lowercases the email local part unchanged. */
    private static String expectedUsernameOf(String email) {
        return email.substring(0, email.indexOf('@'));
    }
}
