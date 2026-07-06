package com.hhtuann.backend.authentication;

import tools.jackson.databind.JsonNode;
import com.hhtuann.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code POST /api/auth/login} against a real PostgreSQL
 * instance. Uses a mutable clock so the 15-minute lockout window can be advanced
 * deterministically. Transactional so rows are rolled back.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class LoginIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(Instant.now());
    }

    @Test
    void loginByUsernameReturnsTokenAndSetsRefreshCookie() throws Exception {
        String username = unique("login");
        registerStudent(username, "Passw0rd!");

        var result = login(username, "Passw0rd!");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("accessToken").asString()).isNotBlank();
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.get("expiresInSeconds").asLong()).isEqualTo(900);
        assertThat(body.get("roles").get(0).asString()).isEqualTo("STUDENT");

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("quizopia_refresh=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains("Path=/api/auth");
    }

    @Test
    void loginByEmailAndCaseInsensitiveIdentifier() throws Exception {
        String username = unique("emaillogin");
        registerStudent(username, "Passw0rd!");

        // login by email (UPPERCASED) -> same user, case-insensitive
        int status = login(username.toUpperCase() + "@EXAMPLE.COM", "Passw0rd!").getResponse().getStatus();
        assertThat(status).isEqualTo(200);
    }

    @Test
    void wrongPasswordAndUnknownUserShareTheSameErrorCode() throws Exception {
        String username = unique("wrongpw");
        registerStudent(username, "Passw0rd!");

        int wrongStatus = login(username, "WrongPass1!").getResponse().getStatus();
        int unknownStatus = login("no-such-user-" + UUID.randomUUID(), "WrongPass1!").getResponse().getStatus();

        assertThat(wrongStatus).isEqualTo(401);
        assertThat(unknownStatus).isEqualTo(401);
        assertThat(errorCode(login(username, "WrongPass1!"))).isEqualTo("AUTH_INVALID_CREDENTIALS");
        assertThat(errorCode(login("no-such-user-" + UUID.randomUUID(), "WrongPass1!")))
                .isEqualTo("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    void fiveFailedAttemptsLockTheAccountForFifteenMinutes() throws Exception {
        String username = unique("lock");
        registerStudent(username, "Passw0rd!");

        for (int i = 0; i < 4; i++) {
            assertThat(login(username, "WrongPass1!").getResponse().getStatus()).isEqualTo(401);
        }
        // 5th failure tips the account into a lock (still 401 invalid this attempt).
        assertThat(login(username, "WrongPass1!").getResponse().getStatus()).isEqualTo(401);

        // Subsequent attempt during the lock window is rejected as locked.
        var locked = login(username, "Passw0rd!");
        assertThat(locked.getResponse().getStatus()).isEqualTo(423);
        assertThat(errorCode(locked)).isEqualTo("AUTH_ACCOUNT_LOCKED");

        // After the lock window elapses, the correct password succeeds again.
        clock.advance(Duration.ofMinutes(16));
        var ok = login(username, "Passw0rd!");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void successfulLoginResetsFailedAttemptsAndLockedUntil() throws Exception {
        String username = unique("reset");
        registerStudent(username, "Passw0rd!");

        // 3 failures (below threshold) then a successful login.
        for (int i = 0; i < 3; i++) {
            login(username, "WrongPass1!");
        }
        assertThat(login(username, "Passw0rd!").getResponse().getStatus()).isEqualTo(200);

        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT failed_login_attempts, locked_until FROM users WHERE username = :u")
                .setParameter("u", username)
                .getSingleResult();
        assertThat(((Number) row[0]).intValue()).isEqualTo(0);
        assertThat(row[1]).isNull();
    }

    @Test
    void disabledAccountCannotLogIn() throws Exception {
        String username = unique("disabled");
        registerStudent(username, "Passw0rd!");
        setStatus(username, "DISABLED");

        var result = login(username, "Passw0rd!");
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(result)).isEqualTo("AUTH_ACCOUNT_DISABLED");
    }

    @Test
    void pendingAccountCannotLogIn() throws Exception {
        String username = unique("pending");
        registerStudent(username, "Passw0rd!");
        setStatus(username, "PENDING");

        var result = login(username, "Passw0rd!");
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(result)).isEqualTo("AUTH_ACCOUNT_PENDING");
    }

    @Test
    void refreshSessionStoresOnlyTheHashNotTheToken() throws Exception {
        String username = unique("hash");
        registerStudent(username, "Passw0rd!");
        String cookieValue = extractRefreshCookieValue(login(username, "Passw0rd!"));

        String storedHash = (String) entityManager.createNativeQuery(
                        "SELECT token_hash FROM refresh_sessions WHERE user_id = (SELECT id FROM users WHERE username = :u)")
                .setParameter("u", username)
                .getSingleResult();

        assertThat(storedHash).matches("^[0-9a-f]{64}$");
        assertThat(storedHash).isNotEqualTo(cookieValue);
        assertThat(cookieValue).doesNotMatch("^[0-9a-f]{64}$");
    }

    // ---------- helpers ----------

    private String errorCode(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asString();
    }

    private void setStatus(String username, String status) {
        entityManager.createNativeQuery("UPDATE users SET status = :s WHERE username = :u")
                .setParameter("s", status)
                .setParameter("u", username)
                .executeUpdate();
        // Native updates bypass the persistence context; clear it so the next
        // service read sees the new status instead of the cached ACTIVE user.
        entityManager.clear();
    }

    private void registerStudent(String username, String password) throws Exception {
        String json = studentRegisterJson(username, emailFor(username), password, username + " Name", "+84991234567", "001234567890");
        register(json);
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
