package com.hhtuann.backend.authentication;

import com.hhtuann.backend.identity.domain.model.RefreshSession;
import com.hhtuann.backend.identity.repository.RefreshSessionRepository;
import com.hhtuann.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code POST /api/auth/refresh}: rotation, reuse
 * detection (whole-family revocation), expired, revoked and missing-cookie
 * paths. Runs against a real PostgreSQL instance; transactional so rows roll
 * back.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RefreshIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private RefreshSessionRepository refreshSessionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void rotationIssuesNewTokenRevokesOldAndKeepsFamilyExpiry() throws Exception {
        String username = unique("rotate");
        registerStudent(username);
        String oldToken = extractRefreshCookieValue(login(username, "Passw0rd!"));

        RefreshSession oldBefore = onlySession(username);
        UUID oldId = oldBefore.getId();
        UUID familyId = oldBefore.getFamilyId();
        Instant familyExpiry = oldBefore.getExpiresAt();

        MvcResult result = mockMvc.perform(refreshRequest(oldToken)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        String newToken = extractRefreshCookieValue(result);
        assertThat(newToken).isNotEqualTo(oldToken);

        RefreshSession oldAfter = refreshSessionRepository.findById(oldId).orElseThrow();
        RefreshSession newest = oldAfter.getReplacedBySession();

        assertThat(oldAfter.getRevokedAt()).isNotNull();
        assertThat(oldAfter.getRevokeReason()).isEqualTo("ROTATED");
        assertThat(newest).isNotNull();
        assertThat(newest.getParentSession().getId()).isEqualTo(oldId);
        assertThat(newest.getFamilyId()).isEqualTo(familyId);
        // Family lifetime is not extended: the new session keeps the old expiry.
        assertThat(newest.getExpiresAt()).isEqualTo(familyExpiry);
        assertThat(newest.getTokenHash()).matches("^[0-9a-f]{64}$");
        assertThat(newest.getTokenHash()).isNotEqualTo(oldAfter.getTokenHash());
    }

    @Test
    void reusingRotatedTokenRevokesEntireFamily() throws Exception {
        String username = unique("reuse");
        registerStudent(username);
        String oldToken = extractRefreshCookieValue(login(username, "Passw0rd!"));
        UUID familyId = onlySession(username).getFamilyId();

        // First refresh rotates the token.
        mockMvc.perform(refreshRequest(oldToken)).andReturn().getResponse().getStatus();

        // Reusing the old (rotated) token triggers reuse detection.
        MvcResult reuse = mockMvc.perform(refreshRequest(oldToken)).andReturn();
        assertThat(reuse.getResponse().getStatus()).isEqualTo(401);
        assertThat(errorCode(reuse)).isEqualTo("AUTH_REFRESH_TOKEN_REUSE_DETECTED");

        // Every session in the family is now revoked.
        Number unrevoked = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM refresh_sessions WHERE family_id = :f AND revoked_at IS NULL")
                .setParameter("f", familyId)
                .getSingleResult();
        assertThat(unrevoked.intValue()).isZero();
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        String username = unique("expired");
        registerStudent(username);
        String token = extractRefreshCookieValue(login(username, "Passw0rd!"));

        expireSessionOf(username);

        MvcResult result = mockMvc.perform(refreshRequest(token)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(errorCode(result)).isEqualTo("AUTH_REFRESH_TOKEN_EXPIRED");
    }

    @Test
    void revokedRefreshTokenWithoutReplacementIsRejected() throws Exception {
        String username = unique("revoked");
        registerStudent(username);
        String token = extractRefreshCookieValue(login(username, "Passw0rd!"));

        // Logout revokes the session with no replacement.
        mockMvc.perform(logoutRequest(token)).andReturn();

        MvcResult result = mockMvc.perform(refreshRequest(token)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(errorCode(result)).isEqualTo("AUTH_REFRESH_TOKEN_REVOKED");
    }

    @Test
    void missingRefreshCookieIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(refreshRequest(null)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(errorCode(result)).isEqualTo("AUTH_REFRESH_TOKEN_INVALID");
    }

    // ---------- helpers ----------

    private String errorCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asString();
    }

    private void registerStudent(String username) throws Exception {
        String json = studentRegisterJson(username, emailFor(username), "Passw0rd!", username + " Name", "+84991234567", "001234567890");
        register(json);
    }

    private RefreshSession onlySession(String username) {
        UUID id = (UUID) entityManager.createNativeQuery(
                        "SELECT id FROM refresh_sessions WHERE user_id = (SELECT id FROM users WHERE username = :u)")
                .setParameter("u", username)
                .getSingleResult();
        return refreshSessionRepository.findById(id).orElseThrow();
    }

    private void expireSessionOf(String username) {
        // Keep expires_at > created_at (constraint) while moving both into the
        // past so the session is treated as expired but remains constraint-valid.
        entityManager.createNativeQuery(
                        "UPDATE refresh_sessions SET created_at = :created, expires_at = :expires "
                                + "WHERE user_id = (SELECT id FROM users WHERE username = :u)")
                .setParameter("created", Instant.now().minusSeconds(7200))
                .setParameter("expires", Instant.now().minusSeconds(3600))
                .setParameter("u", username)
                .executeUpdate();
        entityManager.clear();
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
