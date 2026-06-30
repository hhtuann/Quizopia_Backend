package com.hhtuann.backend.authentication;

import com.hhtuann.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration tests for {@code POST /api/auth/register} against a real PostgreSQL
 * instance (Testcontainers). Each test is transactional so registered rows are
 * rolled back.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RegisterIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private EntityManager entityManager;

    @Test
    void studentRegistersSuccessfullyWithExpectedFields() throws Exception {
        String username = unique("student");
        MvcResultHolder result = registerStudent(username, "Passw0rd!");

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(result.body().get("roles").get(0).asText()).isEqualTo("STUDENT");
        assertThat(result.body().get("username").asText()).isEqualTo(username);
        assertThat(result.body().get("phone").asText()).isEqualTo("+84991234567");
        assertThat(result.body().get("nationalId").asText()).isEqualTo("001234567890");
        // No secrets or internals in the response.
        assertNoSensitiveFields(result.body());
    }

    @Test
    void nullAccountTypeDefaultsToStudent() throws Exception {
        String json = """
                {"username":"%s","email":"%s","password":"Passw0rd!","displayName":"Default User","phone":"+8499","nationalId":"123456"}
                """.formatted(unique("default"), emailFor(unique("default")));

        int status = mockMvc.perform(post("/api/auth/register")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(201);
    }

    @Test
    void teacherRegistersWithCorrectInviteCode() throws Exception {
        MvcResultHolder result = registerTeacher(unique("teacher"), TEACHER_INVITE);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("roles").get(0).asText()).isEqualTo("TEACHER");
    }

    @Test
    void teacherWithWrongInviteCodeIsRejected() throws Exception {
        registerTeacher(unique("teacher-ok"), TEACHER_INVITE); // first teacher succeeds
        MvcResultHolder result = registerTeacherRaw(unique("teacher-bad"), "wrong-invite");

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.body().get("code").asText()).isEqualTo("AUTH_TEACHER_INVITE_INVALID");
    }

    @Test
    void duplicateUsernameReturnsConflict() throws Exception {
        String username = unique("dup");
        registerStudent(username, "Passw0rd!");
        MvcResultHolder result = registerStudentRaw(username, emailFor(unique("dup-other")));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body().get("code").asText()).isEqualTo("AUTH_USERNAME_ALREADY_EXISTS");
    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        String email = emailFor(unique("dupemail"));
        registerStudentRaw(unique("dupemail-a"), email);
        MvcResultHolder result = registerStudentRaw(unique("dupemail-b"), email);

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body().get("code").asText()).isEqualTo("AUTH_EMAIL_ALREADY_EXISTS");
    }

    @Test
    void usernameContainingAtSignIsRejected() throws Exception {
        String json = studentRegisterJson("has@at", emailFor(unique("at")), "Passw0rd!", "At User", "+8499", "123456");
        int status = mockMvc.perform(post("/api/auth/register")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
    }

    @Test
    void passwordShorterThanEightIsRejected() throws Exception {
        String json = studentRegisterJson(unique("shortpw"), emailFor(unique("shortpw")), "Short1!", "Short", "+8499", "123456");
        int status = mockMvc.perform(post("/api/auth/register")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
    }

    @Test
    void phoneAndNationalIdAreStoredAsCiphertextAndPasswordIsHashed() throws Exception {
        String username = unique("cipher");
        registerStudent(username, "Passw0rd!");

        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT password_hash, phone_encrypted, national_id_encrypted FROM users WHERE username = :u")
                .setParameter("u", username)
                .getSingleResult();
        String passwordHash = (String) row[0];
        String phoneEncrypted = (String) row[1];
        String nationalIdEncrypted = (String) row[2];

        assertThat(phoneEncrypted).startsWith("v1:");
        assertThat(phoneEncrypted).doesNotContain("+84991234567");
        assertThat(nationalIdEncrypted).startsWith("v1:");
        assertThat(nationalIdEncrypted).doesNotContain("001234567890");
        assertThat(passwordHash).doesNotContain("Passw0rd!");
    }

    // ---------- helpers ----------

    private static void assertNoSensitiveFields(JsonNode body) {
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(body.has("phoneEncrypted")).isFalse();
        assertThat(body.has("nationalIdEncrypted")).isFalse();
        assertThat(body.has("tokenVersion")).isFalse();
        assertThat(body.has("accessToken")).isFalse();
        assertThat(body.has("teacherInviteCode")).isFalse();
    }

    private MvcResultHolder registerStudent(String username, String password) throws Exception {
        return registerStudentRaw(username, emailFor(username));
    }

    private MvcResultHolder registerStudentRaw(String username, String email) throws Exception {
        String json = studentRegisterJson(username, email, "Passw0rd!", username + " Name", "+84991234567", "001234567890");
        return performRegister(json);
    }

    private MvcResultHolder registerTeacher(String username, String invite) throws Exception {
        return registerTeacherRaw(username, invite);
    }

    private MvcResultHolder registerTeacherRaw(String username, String invite) throws Exception {
        String json = teacherRegisterJson(username, emailFor(username), "Passw0rd!", username + " Name", "+84991234567", "001234567890", invite);
        return performRegister(json);
    }

    private MvcResultHolder performRegister(String json) throws Exception {
        var result = register(json);
        return new MvcResultHolder(result.getResponse().getStatus(),
                objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }

    private record MvcResultHolder(int status, JsonNode body) {
    }
}
