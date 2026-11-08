package com.quizopia.backend.community;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * AuthN-layer outcomes for the community owner routes (ADR-006 §7 auth
 * matrix) using REAL access tokens through the actual JWT decoder +
 * {@code QuizopiaJwtAuthenticationConverter} (per-request live-DB
 * revalidation):
 *
 * <ul>
 *   <li><b>DISABLED</b> — a token minted for a then-active teacher is
 *       rejected with 401 after the account is disabled (token stays
 *       structurally valid; the converter re-reads status).</li>
 *   <li><b>UNVERIFIED</b> — an unverified-email account cannot log in at
 *       all, so it can never obtain a token for any authenticated route;
 *       documented at the AuthN layer per §7 (service-layer verified
 *       checks are additionally covered as defense-in-depth in
 *       {@code CommunityPublicationOwnerApiIntegrationTests}).</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
class CommunityAuthNIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void disabledUserTokenIsRejected401AtAuthN() throws Exception {
        String username = "commdis" + System.nanoTime();
        String email = username + "@t.example.com";
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));
        // The owner routes require TEACHER; roles resolve live per request.
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.id, r.id FROM users u, roles r
                WHERE u.username = ? AND r.code = 'TEACHER'
                """, username);

        // Sanity: while ACTIVE the route is reachable (200, not 401/403).
        int whileActive = mockMvc.perform(get("/api/community/me/publications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(whileActive).isEqualTo(200);

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE username = ?", username);

        int afterDisable = mockMvc.perform(get("/api/community/me/publications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(afterDisable).isEqualTo(401);
        afterDisable = mockMvc.perform(get("/api/community/me/bookmarks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(afterDisable)
                .as("bookmark shelf list is unreachable for a disabled account (#45)")
                .isEqualTo(401);

        afterDisable = mockMvc.perform(put("/api/community/publications/1/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(afterDisable)
                .as("bookmark save is unreachable for a disabled account (#45)")
                .isEqualTo(401);

        afterDisable = mockMvc.perform(delete("/api/community/publications/1/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(afterDisable)
                .as("bookmark delete is unreachable for a disabled account (#45)")
                .isEqualTo(401);

        afterDisable = mockMvc.perform(post("/api/community/publications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"examId\":1,\"title\":\"x\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(afterDisable).isEqualTo(401);
    }

    @Test
    void unverifiedUserCannotLogInSoCommunityRoutesAreUnreachable() throws Exception {
        String username = "communv" + System.nanoTime();
        String email = username + "@t.example.com";
        MvcResult registration = register(
                studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"));
        MvcResult loginResult = login(username, "Passw0rd!");
        assertThat(loginResult.getResponse().getStatus())
                .as("unverified accounts cannot authenticate (AuthN-layer denial, ADR-006 §7)")
                .isEqualTo(403);
        assertThat(loginResult.getResponse().getContentAsString()).doesNotContain("accessToken");
    }
}
