package com.hhtuann.backend.academic;

import com.hhtuann.backend.academic.domain.model.AcademicStatus;
import com.hhtuann.backend.academic.domain.model.GradeLevel;
import com.hhtuann.backend.academic.domain.model.School;
import com.hhtuann.backend.academic.domain.model.Subject;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.GradeLevelRepository;
import com.hhtuann.backend.academic.repository.SchoolRepository;
import com.hhtuann.backend.academic.repository.SubjectRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
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
 * Integration tests for the Subject CRUD + grade-level endpoints (MockMvc +
 * Testcontainers). Mirrors {@code SubjectApiIntegrationTests}. The create/
 * update/status flows run as ACADEMIC_ADMIN (the only role seeded with
 * SUBJECT_CREATE/UPDATE/STATUS_UPDATE); cross-role 403, 404, 409 and 400 paths
 * are covered too.
 *
 * <p>Bodies are hand-crafted JSON (ASCII only) to avoid coupling the test to
 * Jackson, which is not a direct test-scope dependency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class SubjectCrudIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private GradeLevelRepository glRepo;
    @Autowired private SubjectRepository subjectRepo;
    @Autowired private TeacherProfileRepository teacherRepo;
    @Autowired private JdbcTemplate jdbc;

    private Long adminUserId;
    private Long teacherUserId;
    private Long studentUserId;
    private Long schoolId;
    private Long otherSchoolId;
    private Long gl1Id;
    private Long otherGlId;
    private Long subjectId;

    @BeforeEach
    void setUp() {
        User admin = userRepo.saveAndFlush(new User("crud-admin", "ca@test.com", "hash", "Crud Admin"));
        adminUserId = admin.getId();
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'ACADEMIC_ADMIN'", adminUserId);

        User teacher = userRepo.saveAndFlush(new User("crud-teacher", "ct@test.com", "hash", "Crud Teacher"));
        teacherUserId = teacher.getId();
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);

        User student = userRepo.saveAndFlush(new User("crud-student", "cs@test.com", "hash", "Crud Student"));
        studentUserId = student.getId();
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", studentUserId);

        schoolId = schoolRepo.saveAndFlush(new School("CRUD-SCH", "Crud School")).getId();
        otherSchoolId = schoolRepo.saveAndFlush(new School("CRUD-SCH2", "Other Crud School")).getId();

        GradeLevel gl1 = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL10", "Grade 10"));
        gl1Id = gl1.getId();
        glRepo.saveAndFlush(new GradeLevel(schoolId, "GL11", "Grade 11"));
        otherGlId = glRepo.saveAndFlush(new GradeLevel(otherSchoolId, "OGL", "Other GL")).getId();

        subjectId = subjectRepo.saveAndFlush(new Subject(schoolId, gl1Id, "GEN-MATH", "Math")).getId();

        teacherRepo.saveAndFlush(new TeacherProfile(teacherUserId, schoolId, "TC-CRUD"));
    }

    // ==================== POST create ====================

    @Test
    void createSubject_returns201AndPersists() throws Exception {
        mockMvc.perform(post("/api/subjects")
                        .with(jwtFor(adminUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("NEW-CHEM", "Chemistry", "Chemistry desc", schoolId, gl1Id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.schoolId").value(schoolId.intValue()))
                .andExpect(jsonPath("$.gradeLevelId").value(gl1Id.intValue()))
                .andExpect(jsonPath("$.code").value("NEW-CHEM"))
                .andExpect(jsonPath("$.name").value("Chemistry"))
                .andExpect(jsonPath("$.description").value("Chemistry desc"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Subject saved = subjectRepo.findBySchoolIdAndGradeLevelIdAndCodeIgnoreCase(schoolId, gl1Id, "NEW-CHEM")
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getStatus()).isEqualTo(AcademicStatus.ACTIVE);
    }

    @Test
    void createSubject_duplicateCode_returns409() throws Exception {
        // "GEN-MATH" already exists for (school, gl1) from setUp.
        mockMvc.perform(post("/api/subjects")
                        .with(jwtFor(adminUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("GEN-MATH", "Dup", null, schoolId, gl1Id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMIC_SUBJECT_CODE_CONFLICT"));
    }

    @Test
    void createSubject_unknownSchool_returns404() throws Exception {
        mockMvc.perform(post("/api/subjects")
                        .with(jwtFor(adminUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("X", "X", null, 9_999_999L, gl1Id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_SCHOOL_NOT_FOUND"));
    }

    @Test
    void createSubject_gradeLevelNotInSchool_returns404() throws Exception {
        // otherGlId belongs to otherSchool, not schoolId.
        mockMvc.perform(post("/api/subjects")
                        .with(jwtFor(adminUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Y", "Y", null, schoolId, otherGlId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_GRADE_LEVEL_NOT_FOUND"));
    }

    @Test
    void createSubject_teacherRole_returns403() throws Exception {
        mockMvc.perform(post("/api/subjects")
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("T", "T", null, schoolId, gl1Id)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACADEMIC_ACCESS_DENIED"));
    }

    @Test
    void createSubject_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("U", "U", null, schoolId, gl1Id)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createSubject_blankCode_returns400() throws Exception {
        mockMvc.perform(post("/api/subjects")
                        .with(jwtFor(adminUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(" ", "Name", null, schoolId, gl1Id)))
                .andExpect(status().isBadRequest());
    }

    // ==================== PUT update ====================

    @Test
    void updateSubject_updatesNameAndDescription() throws Exception {
        mockMvc.perform(put("/api/subjects/{id}", subjectId)
                        .with(jwtFor(adminUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Math (updated)", "New desc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subjectId.intValue()))
                .andExpect(jsonPath("$.name").value("Math (updated)"))
                .andExpect(jsonPath("$.description").value("New desc"))
                .andExpect(jsonPath("$.code").value("GEN-MATH")); // code immutable

        Subject reloaded = subjectRepo.findById(subjectId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getName()).isEqualTo("Math (updated)");
        org.assertj.core.api.Assertions.assertThat(reloaded.getDescription()).isEqualTo("New desc");
    }

    @Test
    void updateSubject_unknownSubject_returns404() throws Exception {
        mockMvc.perform(put("/api/subjects/{id}", 9_999_999L)
                        .with(jwtFor(adminUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("X", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_SUBJECT_NOT_FOUND"));
    }

    // ==================== PUT status ====================

    @Test
    void updateSubjectStatus_changesStatus() throws Exception {
        mockMvc.perform(put("/api/subjects/{id}/status", subjectId)
                        .with(jwtFor(adminUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("ARCHIVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subjectId.intValue()))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        org.assertj.core.api.Assertions.assertThat(subjectRepo.findById(subjectId).orElseThrow().getStatus())
                .isEqualTo(AcademicStatus.ARCHIVED);
    }

    @Test
    void updateSubjectStatus_unknownSubject_returns404() throws Exception {
        mockMvc.perform(put("/api/subjects/{id}/status", 9_999_999L)
                        .with(jwtFor(adminUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("ACTIVE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_SUBJECT_NOT_FOUND"));
    }

    @Test
    void updateSubjectStatus_teacherRole_returns403() throws Exception {
        mockMvc.perform(put("/api/subjects/{id}/status", subjectId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("ARCHIVED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACADEMIC_ACCESS_DENIED"));
    }

    // ==================== GET grade-levels ====================

    @Test
    void listGradeLevels_adminWithSchoolId_returnsList() throws Exception {
        mockMvc.perform(get("/api/grade-levels")
                        .param("schoolId", schoolId.toString())
                        .with(jwtFor(adminUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)) // GL10 + GL11
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.items[0].code").exists())
                .andExpect(jsonPath("$.items[0].name").exists())
                .andExpect(jsonPath("$.items[0].sortOrder").exists());
    }

    @Test
    void listGradeLevels_teacherUsesProfileSchool() throws Exception {
        // TEACHER has a profile in schoolId (setUp) → ignores the param, uses profile school.
        mockMvc.perform(get("/api/grade-levels")
                        .param("schoolId", otherSchoolId.toString()) // intentionally wrong school
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                // Profile school is schoolId (GL10+GL11), NOT otherSchoolId.
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void listGradeLevels_studentRole_returns403() throws Exception {
        mockMvc.perform(get("/api/grade-levels")
                        .param("schoolId", schoolId.toString())
                        .with(jwtFor(studentUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACADEMIC_ACCESS_DENIED"));
    }

    @Test
    void listGradeLevels_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/grade-levels").param("schoolId", schoolId.toString()))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Helpers ====================

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(Long userId) {
        return jwt().jwt(token -> token.subject(userId.toString()).claim("token_version", 0));
    }

    private static String createBody(String code, String name, String description, Long schoolId, Long gradeLevelId) {
        String desc = description == null ? "null" : "\"" + description + "\"";
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"description\":" + desc
                + ",\"schoolId\":" + schoolId + ",\"gradeLevelId\":" + gradeLevelId + "}";
    }

    private static String updateBody(String name, String description) {
        String desc = description == null ? "null" : "\"" + description + "\"";
        return "{\"name\":\"" + name + "\",\"description\":" + desc + "}";
    }

    private static String statusBody(String status) {
        return "{\"status\":\"" + status + "\"}";
    }
}
