package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NOT @Transactional: the epoch bump must be COMMITTED before the call,
 * mirroring a password change that raced this authorization (the JWT passed
 * the converter before the bump, then reached the service with claim 0 while
 * the locked row already reads 1).
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
class GoogleLinkStaleEpochIntegrationTests extends AbstractAuthenticationIntegrationTests {
    @Autowired private GoogleLinkService googleLinkService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.quizopia.backend.testsupport.MutableClock clock;

    private Long userId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now());
        String username = "linkepoch" + System.nanoTime();
        registerAndVerify(username, username + "@example.test", "Passw0rd!");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    @Test
    void staleJwtEpochCannotStartALinkFlowOrBurnAPasswordAttempt() {
        int before = jdbc.queryForObject(
                "SELECT failed_login_attempts FROM users WHERE id = ?", Integer.class, userId);
        jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", userId);
        assertThat(jdbc.queryForObject(
                "SELECT token_version FROM users WHERE id = ?", Integer.class, userId)).isEqualTo(1);

        assertThatThrownBy(() -> googleLinkService.startLink(userId, 0, "Passw0rd!"))
                .isInstanceOf(AuthenticationException.class);

        int after = jdbc.queryForObject(
                "SELECT failed_login_attempts FROM users WHERE id = ?", Integer.class, userId);
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM oauth_link_requests WHERE user_id = ?", Integer.class, userId)).isZero();
    }
}
