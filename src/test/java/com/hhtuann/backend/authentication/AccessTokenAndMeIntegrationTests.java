package com.hhtuann.backend.authentication;

import tools.jackson.databind.JsonNode;
import com.hhtuann.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the JWT access token and {@code GET /api/auth/me}.
 * Verifies token claims, absence of sensitive claims, the /me payload, the
 * missing-token / token-version-mismatch / non-ACTIVE user failure modes, and
 * that authorities carry roles and permissions.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class AccessTokenAndMeIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private EntityManager entityManager;

    @Test
    void accessTokenCarriesOnlyIdentityClaims() throws Exception {
        String token = loginAndFetchToken(unique("claims"));

        Jwt jwt = jwtDecoder.decode(token);
        assertThat(jwt.getSubject()).isNotBlank();
        assertThat(jwt.getClaim("username").toString()).startsWith("claims");
        assertThat(jwt.hasClaim("roles")).isTrue();
        assertThat(((Number) jwt.getClaim("token_version")).intValue()).isZero();
        assertThat(jwt.getId()).isNotBlank();
        // iss is a plain string (not a URL), so read the raw claim rather than
        // Jwt.getIssuer(), which would try to parse it as a URL.
        assertThat(jwt.getClaim("iss").toString()).isEqualTo("quizopia-backend");
        assertThat(jwt.getAudience()).contains("quizopia-web");
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isNotNull();
        assertThat(jwt.getClaims().keySet())
                .doesNotContain("email", "phone", "password_hash", "permissions");
    }

    @Test
    void meReturnsOwnerProfileWithDecryptedPersonalData() throws Exception {
        String username = unique("me");
        registerStudent(username, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));

        MvcResult result = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("username").asString()).isEqualTo(username);
        assertThat(body.get("phone").asString()).isEqualTo("+84991234567");
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("roles").get(0).asString()).isEqualTo("STUDENT");
        assertThat(body.get("permissions").toString()).contains("ATTEMPT_START", "EXAM_READ");
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(body.has("phoneEncrypted")).isFalse();
        assertThat(body.has("tokenVersion")).isFalse();
    }

    @Test
    void meWithoutTokenReturns401() throws Exception {
        int status = mockMvc.perform(get("/api/auth/me"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    @Test
    void tokenVersionMismatchReturns401() throws Exception {
        String username = unique("tv");
        registerStudent(username, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));

        entityManager.createNativeQuery("UPDATE users SET token_version = 5 WHERE username = :u")
                .setParameter("u", username)
                .executeUpdate();
        entityManager.clear();

        int status = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    @Test
    void nonActiveUserTokenIsRejected() throws Exception {
        String username = unique("nonactive");
        registerStudent(username, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));

        entityManager.createNativeQuery("UPDATE users SET status = 'DISABLED' WHERE username = :u")
                .setParameter("u", username)
                .executeUpdate();
        entityManager.clear();

        int status = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    // ---------- helpers ----------

    private String loginAndFetchToken(String username) throws Exception {
        registerStudent(username, "Passw0rd!");
        return extractAccessToken(login(username, "Passw0rd!"));
    }

    private void registerStudent(String username, String password) throws Exception {
        String json = studentRegisterJson(username, emailFor(username), password, username + " Name", "+84991234567");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
