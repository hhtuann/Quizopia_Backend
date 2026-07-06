package com.hhtuann.backend.authentication;

import com.hhtuann.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration tests for CORS and the cookie-endpoint origin check.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class OriginCorsIntegrationTests extends AbstractAuthenticationIntegrationTests {

    private static final String ALLOWED = "http://localhost:3000";
    private static final String EVIL = "http://evil.example.com";

    @Test
    void refreshWithAllowedOriginSucceeds() throws Exception {
        String username = unique("origin-ok");
        registerStudent(username);
        String cookie = extractRefreshCookieValue(login(username, "Passw0rd!"));

        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .header("Origin", ALLOWED)
                        .cookie(new jakarta.servlet.http.Cookie(REFRESH_COOKIE, cookie)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void refreshWithDisallowedOriginIsRejected() throws Exception {
        String username = unique("origin-bad");
        registerStudent(username);
        String cookie = extractRefreshCookieValue(login(username, "Passw0rd!"));

        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .header("Origin", EVIL)
                        .cookie(new jakarta.servlet.http.Cookie(REFRESH_COOKIE, cookie)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(result)).isEqualTo("AUTH_ORIGIN_NOT_ALLOWED");
    }

    @Test
    void logoutWithDisallowedOriginIsRejected() throws Exception {
        String username = unique("origin-logout");
        registerStudent(username);
        String cookie = extractRefreshCookieValue(login(username, "Passw0rd!"));

        MvcResult result = mockMvc.perform(post("/api/auth/logout")
                        .header("Origin", EVIL)
                        .cookie(new jakarta.servlet.http.Cookie(REFRESH_COOKIE, cookie)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(result)).isEqualTo("AUTH_ORIGIN_NOT_ALLOWED");
    }

    @Test
    void preflightFromLocalhostIsAllowed() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/auth/refresh")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isBetween(200, 299);
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
    }

    private String errorCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asString();
    }

    private void registerStudent(String username) throws Exception {
        String json = studentRegisterJson(username, emailFor(username), "Passw0rd!", username + " Name", "+84991234567", "001234567890");
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
