package com.quizopia.backend.testsupport;

import com.quizopia.backend.notification.email.CaptureOtpMailSender;
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
 * common annotations and JSON helpers to register, verify the email OTP (from
 * the in-memory capture mailbox), log in and read tokens/cookies without
 * duplicating boilerplate in every test class. Concrete classes still carry
 * {@code @SpringBootTest} + the Testcontainers import.
 *
 * <p>Identity-M1: {@link #registerAndVerify(String)} registers a student and
 * completes the OTP verification so subsequent logins succeed; the raw
 * {@link #login(String, String)} still exposes the unverified state to tests
 * that assert the gate itself.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractAuthenticationIntegrationTests {

    protected static final String REFRESH_COOKIE = "quizopia_refresh";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    protected CaptureOtpMailSender otpMailbox;

    protected String studentRegisterJson(String username, String email, String password,
            String displayName, String phone) {
        return """
                {"username":"%s","email":"%s","password":"%s","displayName":"%s","phone":"%s","accountType":"STUDENT"}
                """.formatted(username, email, password, displayName, phone);
    }

    /** Registration payload that tries to self-select the TEACHER role. */
    protected String teacherRegisterJson(String username, String email, String password,
            String displayName, String phone) {
        return """
                {"username":"%s","email":"%s","password":"%s","displayName":"%s","phone":"%s","accountType":"TEACHER"}
                """.formatted(username, email, password, displayName, phone);
    }

    protected String verifyEmailJson(String email, String otp) {
        return """
                {"email":"%s","otp":"%s"}
                """.formatted(email, otp);
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

    /** Submits the OTP currently captured for the email. */
    protected MvcResult verifyEmail(String email) throws Exception {
        String otp = otpMailbox.latestOtpFor(email);
        return mockMvc.perform(post("/api/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyEmailJson(email, otp)))
                .andReturn();
    }

    /** Registers a STUDENT and completes OTP verification in one step. */
    protected MvcResult registerAndVerify(String username, String email, String password) throws Exception {
        MvcResult registration = register(
                studentRegisterJson(username, email, password, username + " Name", "+84991234567"));
        assert registration.getResponse().getStatus() == 201
                : "registration failed: " + registration.getResponse().getContentAsString();
        MvcResult verification = verifyEmail(email);
        assert verification.getResponse().getStatus() == 200
                : "verification failed: " + verification.getResponse().getContentAsString();
        return registration;
    }

    /**
     * Waits until the capture mailbox has recorded {@code expectedCount}
     * mails for the email (resend delivery runs on a background executor —
     * review R3-M1-03).
     */
    protected void waitForOtpMailCount(String email, int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (otpMailbox.sendCountFor(email) >= expectedCount) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("expected " + expectedCount + " OTP mails for " + email
                + " but saw " + otpMailbox.sendCountFor(email));
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
        tools.jackson.databind.JsonNode root =
                objectMapper.readTree(loginResult.getResponse().getContentAsString());
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
