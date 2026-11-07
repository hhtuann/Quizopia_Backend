package com.quizopia.backend.authentication.oauth;

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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** ADR-004: new mutation DTOs reject unknown fields — no silent ignore, no echo. */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
@Transactional
class StrictMutationDtoIntegrationTests extends AbstractAuthenticationIntegrationTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.quizopia.backend.testsupport.MutableClock clock;

    private String token;
    private String username;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now());
        username = "strict" + System.nanoTime();
        registerAndVerify(username, username + "@example.test", "Passw0rd!");
        token = extractAccessToken(login(username, "Passw0rd!"));
    }

    @Test
    void linkStartWithUnknownFieldFailsClosedWithoutEchoOrProofBurn() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/oauth/google/link")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Passw0rd!\",\"ownerId\":1,\"email\":\"attacker@example.test\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ownerId", "attacker@example.test");
        Integer attempts = jdbc.queryForObject(
                "SELECT failed_login_attempts FROM users WHERE username = ?", Integer.class, username);
        assertThat(attempts).isZero();
    }

    @Test
    void exchangeWithUnknownFieldIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"anything\",\"userId\":7}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }
}
