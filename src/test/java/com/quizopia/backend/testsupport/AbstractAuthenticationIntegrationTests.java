package com.quizopia.backend.testsupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base class for MockMvc-based authentication integration tests. Provides the
 * common annotations and JSON helpers to register, log in and read tokens/
 * cookies without duplicating boilerplate in every test class. Concrete classes
 * still carry {@code @SpringBootTest} + the Testcontainers import.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractAuthenticationIntegrationTests {

    protected static final String TEACHER_INVITE = "test-teacher-invite-code-only";
    protected static final String REFRESH_COOKIE = "quizopia_refresh";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected String studentRegisterJson(String username, String email, String password,
                                         String displayName, String phone) {
        return """
                {"username":"%s","email":"%s","password":"%s","displayName":"%s","phone":"%s","accountType":"STUDENT"}
                """.formatted(username, email, password, displayName, phone);
    }

    protected String teacherRegisterJson(String username, String email, String password,
                                         String displayName, String phone, String invite) {
        return """
                {"username":"%s","email":"%s","password":"%s","displayName":"%s","phone":"%s","accountType":"TEACHER","teacherInviteCode":"%s"}
                """.formatted(username, email, password, displayName, phone, invite);
    }

    protected String loginJson(String identifier, String password) {
        return """
                {"identifier":"%s","password":"%s"}
                """.formatted(identifier, password);
    }

    protected MvcResult register(String json) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    protected MvcResult login(String identifier, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(identifier, password)))
                .andReturn();
    }

    protected MockHttpServletRequestBuilder refreshRequest(String cookieValue) {
        var builder = post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON);
        if (cookieValue != null) {
            builder.cookie(new jakarta.servlet.http.Cookie(REFRESH_COOKIE, cookieValue));
        }
        return builder;
    }

    protected MockHttpServletRequestBuilder logoutRequest(String cookieValue) {
        var builder = post("/api/auth/logout");
        if (cookieValue != null) {
            builder.cookie(new jakarta.servlet.http.Cookie(REFRESH_COOKIE, cookieValue));
        }
        return builder;
    }

    protected String extractAccessToken(MvcResult loginResult) throws Exception {
        JsonNode root = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        return root.get("accessToken").asString();
    }

    /**
     * Extracts the refresh-token value from the {@code Set-Cookie} header. MockMvc
     * does not reliably expose it via {@code getCookie}, so the header is parsed.
     */
    protected String extractRefreshCookieValue(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        if (header == null || !header.startsWith(REFRESH_COOKIE + "=")) {
            return null;
        }
        String rest = header.substring((REFRESH_COOKIE + "=").length());
        int semi = rest.indexOf(';');
        return semi < 0 ? rest : rest.substring(0, semi);
    }
}
