package com.quizopia.backend.authentication;

import com.quizopia.backend.account.application.ProfileService;
import com.quizopia.backend.account.dto.UpdateProfileRequest;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.security.encryption.SensitiveDataEncryptor;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ProfileIntegrationTests extends AbstractAuthenticationIntegrationTests {
    @Autowired EntityManager entityManager;
    @Autowired SensitiveDataEncryptor encryptor;
    @Autowired ProfileService profiles;

    @Test
    void replacesOnlyOwnerProjectionEncryptsPhoneAndRecordsRedactedAudit() throws Exception {
        Account owner = account();
        Account other = account();
        Object[] before = protectedState(owner.id());
        String otherName = name(other.id());
        MvcResult result = update(owner.token(), "{\"displayName\":\"  Owner Sentinel  \",\"phone\":\"  +84987654321  \"}", 200);
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("id").asLong()).isEqualTo(owner.id());
        assertThat(body.get("displayName").asString()).isEqualTo("Owner Sentinel");
        assertThat(body.get("phone").asString()).isEqualTo("+84987654321");
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(body.has("phoneEncrypted")).isFalse();
        assertThat(body.has("tokenVersion")).isFalse();
        entityManager.flush();
        String ciphertext = (String) entityManager.createNativeQuery("select phone_encrypted from users where id=:id")
                .setParameter("id", owner.id()).getSingleResult();
        assertThat(ciphertext).doesNotContain("84987654321");
        assertThat(encryptor.decrypt(ciphertext)).isEqualTo("+84987654321");
        assertThat(protectedState(owner.id())).containsExactly(before);
        assertThat(name(other.id())).isEqualTo(otherName);
        String audit = (String) entityManager.createNativeQuery("select details::text from identity_audit_events where user_id=:id and event_type='PROFILE_UPDATED'")
                .setParameter("id", owner.id()).getSingleResult();
        assertThat(objectMapper.readTree(audit)).isEqualTo(objectMapper.readTree("{\"fields\":[\"displayName\",\"phone\"]}"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "userId", "ownerId", "username", "email", "roles", "permissions", "status", "passwordHash", "tokenVersion", "lockedUntil", "emailVerifiedAt", "phoneEncrypted", "nationalId", "schoolId", "googleLinked"})
    void rejectsMassAssignmentWithoutChangingOwner(String field) throws Exception {
        Account owner = account();
        String before = name(owner.id());
        MvcResult response = update(owner.token(), objectMapper.writeValueAsString(Map.of(
                "displayName", "Unsafe", "phone", "+84987654321", field, "private-sentinel")), 400);
        assertThat(response.getResponse().getContentAsString()).doesNotContain("private-sentinel");
        assertThat(name(owner.id())).isEqualTo(before);
        assertThat(auditCount(owner.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"displayName\":null}", "{\"displayName\":\"   \"}", "{\"displayName\":\"bad\\nname\"}", "{\"displayName\":\"ok\",\"phone\":\"123456\"}", "{\"displayName\":\"ok\",\"phone\":\"1234567890123456\"}", "{\"displayName\":\"ok\",\"phone\":\"+84 1234567\"}", "{\"displayName\":\"ok\",\"phone\":\"123-4567\"}"})
    void rejectsInvalidProjection(String json) throws Exception {
        Account owner = account();
        String before = name(owner.id());
        update(owner.token(), json, 400);
        assertThat(name(owner.id())).isEqualTo(before);
        assertThat(auditCount(owner.id())).isZero();
    }

    @Test
    void enforcesLengthBoundariesAndNullableReplacement() throws Exception {
        Account owner = account();
        update(owner.token(), objectMapper.writeValueAsString(Map.of("displayName", "x".repeat(151))), 400);
        update(owner.token(), objectMapper.writeValueAsString(Map.of("displayName", "x".repeat(150), "phone", "1234567")), 200);
        update(owner.token(), "{\"displayName\":\"x\",\"phone\":\"+123456789012345\"}", 200);
        for (String suffix : new String[]{"", ",\"phone\":null", ",\"phone\":\"   \""}) {
            var result = update(owner.token(), "{\"displayName\":\"x\"" + suffix + "}", 200);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("phone").isNull()).isTrue();
        }
    }

    @Test
    void unauthenticatedAndAdminBoundaryRemainDenied() throws Exception {
        mockMvc.perform(put("/api/account/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"x\"}")).andExpect(status().isUnauthorized());
        Account owner = account();
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/account/profile/" + owner.id()).header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void activeLockBlocksWriteButNotOwnerReadAndExpiredLockAllowsWrite() throws Exception {
        Account owner = account();
        entityManager.createNativeQuery("update users set locked_until=current_timestamp + interval '1 hour' where id=:id")
                .setParameter("id", owner.id()).executeUpdate();
        entityManager.clear();
        update(owner.token(), "{\"displayName\":\"x\"}", 423);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + owner.token())).andExpect(status().isOk());
        assertThat(auditCount(owner.id())).isZero();
        entityManager.createNativeQuery("update users set locked_until=current_timestamp - interval '1 hour' where id=:id")
                .setParameter("id", owner.id()).executeUpdate();
        entityManager.clear();
        update(owner.token(), "{\"displayName\":\"x\"}", 200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status='DISABLED'", "status='PENDING'", "status='LOCKED'", "token_version=token_version+1"})
    void rejectsRevokedOrInactiveSession(String mutation) throws Exception {
        Account owner = account();
        entityManager.createNativeQuery("update users set " + mutation + " where id=:id")
                .setParameter("id", owner.id()).executeUpdate();
        entityManager.clear();
        update(owner.token(), "{\"displayName\":\"x\"}", 401);
        assertThat(auditCount(owner.id())).isZero();
    }

    @Test
    void serviceRechecksFreshEpochAfterAuthenticationSnapshot() throws Exception {
        Account owner = account();
        // Keep a stale managed user while a database writer changes the credential epoch.
        entityManager.find(com.quizopia.backend.identity.domain.model.User.class, owner.id());
        entityManager.createNativeQuery("update users set token_version=token_version+1 where id=:id")
                .setParameter("id", owner.id()).executeUpdate();
        assertThatThrownBy(() -> profiles.update(owner.id(), 0, new UpdateProfileRequest("Unsafe", null)))
                .isInstanceOf(AuthenticationException.class);
        assertThat(name(owner.id())).isNotEqualTo("Unsafe");
        assertThat(auditCount(owner.id())).isZero();
    }

    private Account account() throws Exception {
        String username = "profile" + UUID.randomUUID().toString().substring(0, 8);
        registerAndVerify(username, username + "@example.com", "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));
        Long id = ((Number) entityManager.createNativeQuery("select id from users where username=:u")
                .setParameter("u", username).getSingleResult()).longValue();
        return new Account(id, token);
    }

    private MvcResult update(String token, String body, int expected) throws Exception {
        return mockMvc.perform(put("/api/account/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected)).andReturn();
    }

    private String name(Long id) {
        return (String) entityManager.createNativeQuery("select display_name from users where id=:id")
                .setParameter("id", id).getSingleResult();
    }

    private Object[] protectedState(Long id) {
        return (Object[]) entityManager.createNativeQuery("select username,email,password_hash,status,token_version,email_verified_at,password_changed_at,(select string_agg(role_id::text, ',' order by role_id) from user_roles where user_id=u.id) from users u where id=:id")
                .setParameter("id", id).getSingleResult();
    }

    private long auditCount(Long id) {
        return ((Number) entityManager.createNativeQuery("select count(*) from identity_audit_events where user_id=:id and event_type='PROFILE_UPDATED'")
                .setParameter("id", id).getSingleResult()).longValue();
    }

    private record Account(Long id, String token) {}
}
