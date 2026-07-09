package com.hhtuann.backend.user;

import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the user-management API (MockMvc + Testcontainers). All
 * flows run as SYSTEM_ADMIN; cross-role (TEACHER/STUDENT/ACADEMIC_ADMIN) and
 * unauthenticated paths assert 403/401. Bodies are hand-crafted JSON (ASCII) to
 * avoid coupling the test to Jackson.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class UserManagementIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepo;
    @Autowired private JdbcTemplate jdbc;

    private Long adminId;
    private Long targetId;
    private Long teacherId;
    private Long academicId;

    @BeforeEach
    void setUp() {
        User admin = userRepo.saveAndFlush(new User("um-admin", "um-admin@test.com", "hash", "UM Admin"));
        adminId = admin.getId();
        grantRole(adminId, "SYSTEM_ADMIN");

        User target = userRepo.saveAndFlush(new User("um-target", "um-target@test.com", "hash", "UM Target"));
        targetId = target.getId();
        grantRole(targetId, "STUDENT");

        User teacher = userRepo.saveAndFlush(new User("um-teacher", "um-teacher@test.com", "hash", "UM Teacher"));
        teacherId = teacher.getId();
        grantRole(teacherId, "TEACHER");

        User academic = userRepo.saveAndFlush(new User("um-academic", "um-academic@test.com", "hash", "UM Academic"));
        academicId = academic.getId();
        grantRole(academicId, "ACADEMIC_ADMIN");
    }

    // ==================== GET list ====================

    @Test
    void listUsers_returnsPaginated() throws Exception {
        mockMvc.perform(get("/api/users").with(jwtFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.size").exists())
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.totalPages").exists());
    }

    @Test
    void listUsers_searchFilter() throws Exception {
        mockMvc.perform(get("/api/users").param("search", "um-target").with(jwtFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.username == 'um-target')]").isNotEmpty());
    }

    @Test
    void listUsers_roleFilter() throws Exception {
        mockMvc.perform(get("/api/users").param("role", "TEACHER").with(jwtFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.username == 'um-teacher')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.username == 'um-target')]").isEmpty());
    }

    @Test
    void listUsers_noSensitiveFields() throws Exception {
        mockMvc.perform(get("/api/users").with(jwtFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].phoneEncrypted").doesNotExist())
                .andExpect(jsonPath("$.items[0].nationalIdEncrypted").doesNotExist())
                .andExpect(jsonPath("$.items[0].tokenVersion").doesNotExist());
    }

    // ==================== GET detail ====================

    @Test
    void getUser_detail() throws Exception {
        mockMvc.perform(get("/api/users/{id}", targetId).with(jwtFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetId.intValue()))
                .andExpect(jsonPath("$.username").value("um-target"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("STUDENT"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.phoneEncrypted").doesNotExist());
    }

    @Test
    void getUser_unknown_returns404() throws Exception {
        mockMvc.perform(get("/api/users/{id}", 9_999_999L).with(jwtFor(adminId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ==================== POST create ====================

    @Test
    void createUser_returns201() throws Exception {
        mockMvc.perform(post("/api/users").with(jwtFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserBody("um-new", "um-new@test.com", "Password123", "UM New", "STUDENT", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.username").value("um-new"))
                .andExpect(jsonPath("$.email").value("um-new@test.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("STUDENT"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void createUser_duplicateUsername_returns409() throws Exception {
        mockMvc.perform(post("/api/users").with(jwtFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserBody("um-target", "other-dup@test.com", "Password123", "Dup", "STUDENT", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_USERNAME_ALREADY_EXISTS"));
    }

    @Test
    void createUser_duplicateEmail_returns409() throws Exception {
        mockMvc.perform(post("/api/users").with(jwtFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserBody("um-other", "um-target@test.com", "Password123", "Dup", "STUDENT", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void createUser_blankUsername_returns400() throws Exception {
        mockMvc.perform(post("/api/users").with(jwtFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserBody(" ", "um-blank@test.com", "Password123", "Blank", "STUDENT", null, null)))
                .andExpect(status().isBadRequest());
    }

    // ==================== PUT update ====================

    @Test
    void updateUser_updatesDisplayNameAndEmail() throws Exception {
        mockMvc.perform(put("/api/users/{id}", targetId).with(jwtFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserBody("Renamed", "um-renamed@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed"))
                .andExpect(jsonPath("$.email").value("um-renamed@test.com"));
    }

    @Test
    void updateUser_duplicateEmail_returns409() throws Exception {
        mockMvc.perform(put("/api/users/{id}", targetId).with(jwtFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserBody(null, "um-teacher@test.com"))) // belongs to um-teacher
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void updateUser_unknown_returns404() throws Exception {
        mockMvc.perform(put("/api/users/{id}", 9_999_999L).with(jwtFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserBody("X", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ==================== Status lifecycle ====================

    @Test
    void disable_thenActivate_isIdempotent() throws Exception {
        mockMvc.perform(post("/api/users/{id}/disable", targetId).with(jwtFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        // idempotent: disabling again stays DISABLED
        mockMvc.perform(post("/api/users/{id}/disable", targetId).with(jwtFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(post("/api/users/{id}/activate", targetId).with(jwtFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void lock_setsLockedUntil_unlockClears() throws Exception {
        mockMvc.perform(post("/api/users/{id}/lock", targetId).with(jwtFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedUntil").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE")); // lock does NOT change status
        mockMvc.perform(post("/api/users/{id}/unlock", targetId).with(jwtFor(adminId)))
                .andExpect(status().isOk())
                // lockedUntil null → absent in JSON
                .andExpect(jsonPath("$.lockedUntil").isEmpty());
    }

    // ==================== Role assign ====================

    @Test
    void assignRole_isIdempotent() throws Exception {
        mockMvc.perform(post("/api/users/{id}/roles", targetId).with(jwtFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignRoleBody("TEACHER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@ == 'TEACHER')]").isNotEmpty());
        // repeat assignment is a no-op (200)
        mockMvc.perform(post("/api/users/{id}/roles", targetId).with(jwtFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignRoleBody("TEACHER")))
                .andExpect(status().isOk());
    }

    @Test
    void assignRole_invalidRole_returns400() throws Exception {
        mockMvc.perform(post("/api/users/{id}/roles", targetId).with(jwtFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignRoleBody("BOGUS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_INVALID_ROLE"));
    }

    // ==================== Roles catalog ====================

    @Test
    void listRoles_returnsFour() throws Exception {
        mockMvc.perform(get("/api/roles").with(jwtFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4));
    }

    // ==================== Authorization ====================

    @Test
    void listUsers_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void listUsers_teacher_returns403() throws Exception {
        mockMvc.perform(get("/api/users").with(jwtFor(teacherId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ACCESS_DENIED"));
    }

    @Test
    void createUser_academicAdmin_returns403() throws Exception {
        // ACADEMIC_ADMIN holds USER_READ but is NOT SYSTEM_ADMIN → blocked.
        mockMvc.perform(post("/api/users").with(jwtFor(academicId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserBody("um-x", "um-x@test.com", "Password123", "X", "STUDENT", null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ACCESS_DENIED"));
    }

    @Test
    void listRoles_student_returns403() throws Exception {
        User student = userRepo.saveAndFlush(new User("um-student", "um-student@test.com", "hash", "UM Student"));
        grantRole(student.getId(), "STUDENT");
        mockMvc.perform(get("/api/roles").with(jwtFor(student.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ACCESS_DENIED"));
    }

    // ==================== Helpers ====================

    private void grantRole(Long userId, String roleCode) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = ?", userId, roleCode);
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(Long userId) {
        return jwt().jwt(token -> token.subject(userId.toString()).claim("token_version", 0));
    }

    private static String createUserBody(String username, String email, String password,
                                         String displayName, String accountType,
                                         String phone, String nationalId) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"" + password
                + "\",\"displayName\":\"" + displayName + "\",\"accountType\":\"" + accountType + "\""
                + (phone == null ? "" : ",\"phone\":\"" + phone + "\"")
                + (nationalId == null ? "" : ",\"nationalId\":\"" + nationalId + "\"")
                + "}";
    }

    private static String updateUserBody(String displayName, String email) {
        StringBuilder sb = new StringBuilder("{");
        if (displayName != null) {
            sb.append("\"displayName\":\"").append(displayName).append("\"");
        }
        if (email != null) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append("\"email\":\"").append(email).append("\"");
        }
        return sb.append("}").toString();
    }

    private static String assignRoleBody(String roleCode) {
        return "{\"roleCode\":\"" + roleCode + "\"}";
    }
}
