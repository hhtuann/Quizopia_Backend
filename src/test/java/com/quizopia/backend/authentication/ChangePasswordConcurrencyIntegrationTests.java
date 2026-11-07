package com.quizopia.backend.authentication;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * NOT @Transactional: the racing requests must run on their own connections
 * against committed state (an open test transaction would hide the user from
 * them and also hold row locks).
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
class ChangePasswordConcurrencyIntegrationTests extends AbstractAuthenticationIntegrationTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.quizopia.backend.testsupport.MutableClock clock;

    private String token;
    private String username;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now());
        username = "chpwc" + System.nanoTime();
        registerAndVerify(username, username + "@example.test", "Passw0rd!");
        token = extractAccessToken(login(username, "Passw0rd!"));
    }

    @Test
    void concurrentChangeAndOldPasswordLoginSerializeOnFreshCredential() throws Exception {
        AtomicReference<MvcResult> changeResult = new AtomicReference<>();
        AtomicReference<MvcResult> loginResult = new AtomicReference<>();
        Thread changeThread = new Thread(() -> {
            try {
                changeResult.set(mockMvc.perform(post("/api/account/security/change-password")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"currentPassword":"Passw0rd!","newPassword":"NewPassw0rd!","confirmPassword":"NewPassw0rd!"}
                                        """))
                        .andReturn());
            } catch (Exception ignored) {
            }
        });
        Thread loginThread = new Thread(() -> {
            try {
                loginResult.set(login(username, "Passw0rd!"));
            } catch (Exception ignored) {
            }
        });
        changeThread.start();
        loginThread.start();
        changeThread.join();
        loginThread.join();

        int changeStatus = changeResult.get() == null ? -1 : changeResult.get().getResponse().getStatus();
        int loginStatus = loginResult.get() == null ? -1 : loginResult.get().getResponse().getStatus();
        assertThat(changeStatus).as("change status").isEqualTo(204);

        // A concurrent old-password login may legitimately complete BEFORE the
        // change commits. The security contract is that nothing it received
        // survives the change: its JWT (epoch 0) must be rejected afterwards.
        if (loginStatus == 200) {
            String racingToken = extractAccessToken(loginResult.get());
            String refreshCookie = extractRefreshCookieValue(loginResult.get());
            int meStatus = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/auth/me").header("Authorization", "Bearer " + racingToken))
                    .andReturn().getResponse().getStatus();
            assertThat(meStatus).as("pre-change JWT after change").isEqualTo(401);
            assertThat(mockMvc.perform(refreshRequest(refreshCookie)).andReturn().getResponse().getStatus())
                    .as("pre-change refresh after change").isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("SELECT token_version FROM users WHERE username = ?",
                Integer.class, username)).isEqualTo(1);
        boolean oldWorks = login(username, "Passw0rd!").getResponse().getStatus() == 200;
        boolean newWorks = login(username, "NewPassw0rd!").getResponse().getStatus() == 200;
        assertThat(oldWorks ^ newWorks).isTrue();
    }

    @Test
    void concurrentDuplicateChangeAttemptsProduceExactlyOneWinner() throws Exception {
        AtomicReference<MvcResult> a = new AtomicReference<>();
        AtomicReference<MvcResult> b = new AtomicReference<>();
        Thread first = new Thread(() -> attemptChange(a, "NewPassw0rd!"));
        Thread second = new Thread(() -> attemptChange(b, "OtherPassw0rd!"));
        first.start();
        second.start();
        first.join();
        second.join();
        int winners = 0;
        for (AtomicReference<MvcResult> result : new AtomicReference[]{a, b}) {
            if (result.get() != null && result.get().getResponse().getStatus() == 204) winners++;
        }
        assertThat(winners).isEqualTo(1);
        boolean newWorks = login(username, "NewPassw0rd!").getResponse().getStatus() == 200;
        boolean otherWorks = login(username, "OtherPassw0rd!").getResponse().getStatus() == 200;
        assertThat(newWorks ^ otherWorks).isTrue();
    }

    private void attemptChange(AtomicReference<MvcResult> target, String newPassword) {
        try {
            target.set(mockMvc.perform(post("/api/account/security/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"Passw0rd!","newPassword":"%s","confirmPassword":"%s"}
                                    """.formatted(newPassword, newPassword)))
                    .andReturn());
        } catch (Exception ignored) {
        }
    }
}
