package com.hhtuann.backend.authentication;

import com.hhtuann.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code POST /api/auth/logout}: revokes the current
 * session with reason LOGOUT, clears the cookie, and is idempotent.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class LogoutIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private EntityManager entityManager;

    @Test
    void logoutRevokesCurrentSessionAndClearsCookie() throws Exception {
        String username = unique("logout");
        registerStudent(username);
        String cookie = extractRefreshCookieValue(login(username, "Passw0rd!"));

        var result = mockMvc.perform(logoutRequest(cookie)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(204);

        String clearHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(clearHeader).contains("quizopia_refresh=");
        assertThat(clearHeader).contains("Max-Age=0");
        assertThat(clearHeader).contains("Path=/api/auth");

        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT revoke_reason, revoked_at FROM refresh_sessions "
                                + "WHERE user_id = (SELECT id FROM users WHERE username = :u)")
                .setParameter("u", username)
                .getSingleResult();
        assertThat(row[0]).isEqualTo("LOGOUT");
        assertThat(row[1]).isNotNull();
    }

    @Test
    void logoutWithoutCookieStillReturns204() throws Exception {
        int status = mockMvc.perform(logoutRequest(null))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(204);
    }

    @Test
    void logoutIsIdempotent() throws Exception {
        String username = unique("idem");
        registerStudent(username);
        String cookie = extractRefreshCookieValue(login(username, "Passw0rd!"));

        int first = mockMvc.perform(logoutRequest(cookie)).andReturn().getResponse().getStatus();
        int second = mockMvc.perform(logoutRequest(cookie)).andReturn().getResponse().getStatus();
        assertThat(first).isEqualTo(204);
        assertThat(second).isEqualTo(204);
    }

    // ---------- helpers ----------

    private void registerStudent(String username) throws Exception {
        String json = studentRegisterJson(username, emailFor(username), "Passw0rd!", username + " Name", "+84991234567", "001234567890");
        register(json);
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
