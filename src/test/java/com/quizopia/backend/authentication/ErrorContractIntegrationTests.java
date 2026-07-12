package com.quizopia.backend.authentication;

import tools.jackson.databind.JsonNode;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Asserts the unified {@code ApiError} body shape and stable codes for the
 * required HTTP statuses: 400, 401, 403, 409, 423.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ErrorContractIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Test
    void validationErrorReturns400Body() throws Exception {
        JsonNode body = errorBody(shortPasswordRegister(), "/api/auth/register");
        assertSchema(body, 400, "AUTH_VALIDATION_ERROR", "/api/auth/register");
    }

    @Test
    void invalidCredentialsReturns401Body() throws Exception {
        String username = unique("ec");
        registerStudent(username);
        JsonNode body = errorBody(login(username, "WrongPass1!"), "/api/auth/login");
        assertSchema(body, 401, "AUTH_INVALID_CREDENTIALS", "/api/auth/login");
    }

    @Test
    void teacherInviteInvalidReturns403Body() throws Exception {
        JsonNode body = errorBody(registerTeacherWrongInvite(), "/api/auth/register");
        assertSchema(body, 403, "AUTH_TEACHER_INVITE_INVALID", "/api/auth/register");
    }

    @Test
    void duplicateUsernameReturns409Body() throws Exception {
        String username = unique("dup409");
        registerStudent(username);
        JsonNode body = errorBody(registerStudentRaw(username, emailFor(unique("dup409b"))), "/api/auth/register");
        assertSchema(body, 409, "AUTH_USERNAME_ALREADY_EXISTS", "/api/auth/register");
    }

    @Test
    void lockedAccountReturns423Body() throws Exception {
        String username = unique("lock423");
        registerStudent(username);
        for (int i = 0; i < 5; i++) {
            login(username, "WrongPass1!");
        }
        JsonNode body = errorBody(login(username, "Passw0rd!"), "/api/auth/login");
        assertSchema(body, 423, "AUTH_ACCOUNT_LOCKED", "/api/auth/login");
    }

    // ---------- helpers ----------

    private static void assertSchema(JsonNode body, int status, String code, String path) {
        assertThat(body.has("timestamp")).isTrue();
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("message").asString()).isNotBlank();
        assertThat(body.get("path").asString()).isEqualTo(path);
        assertThat(body.has("traceId")).isTrue();
        // No stack trace leaked.
        assertThat(body.has("stackTrace")).isFalse();
    }

    private JsonNode errorBody(MvcResult result, String expectedPath) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult shortPasswordRegister() throws Exception {
        String json = studentRegisterJson(unique("short"), emailFor(unique("short")), "Short1!", "Short", "+8499");
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
    }

    private MvcResult registerTeacherWrongInvite() throws Exception {
        String json = teacherRegisterJson(unique("teach"), emailFor(unique("teach")), "Passw0rd!", "Teach", "+8499", "wrong");
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
    }

    private void registerStudent(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(studentRegisterJson(username, emailFor(username), "Passw0rd!", username + " Name", "+84991234567")));
    }

    private MvcResult registerStudentRaw(String username, String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567"))).andReturn();
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}
