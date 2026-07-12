package com.quizopia.backend.academic;

import com.quizopia.backend.academic.domain.model.AcademicStatus;
import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.School;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.domain.model.TeacherProfile;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SchoolRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.academic.repository.TeacherProfileRepository;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@code GET /api/subjects} (school-scoped, SUBJECT_READ)
 * using MockMvc + Testcontainers. Mirrors
 * {@code QuestionBankApiIntegrationTests}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class SubjectApiIntegrationTests {

        @Autowired
        private MockMvc mockMvc;
        @Autowired
        private UserRepository userRepo;
        @Autowired
        private SchoolRepository schoolRepo;
        @Autowired
        private GradeLevelRepository glRepo;
        @Autowired
        private SubjectRepository subjectRepo;
        @Autowired
        private TeacherProfileRepository teacherRepo;
        @Autowired
        private org.springframework.jdbc.core.JdbcTemplate jdbc;

        private Long teacherUserId;
        private Long schoolId;
        private Long otherSchoolId;
        private Long gradeLevel1Id;
        private Long gradeLevel2Id;

        @BeforeEach
        void setUp() {
                User teacher = userRepo.saveAndFlush(
                                new User("subj-teacher", "st@test.com", "hash", "Subject Teacher"));
                teacherUserId = teacher.getId();
                // TEACHER role → effective SUBJECT_READ (V3 seed).
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);

                School school = schoolRepo.saveAndFlush(new School("SUBJ-SCH", "Subject School"));
                schoolId = school.getId();
                otherSchoolId = schoolRepo.saveAndFlush(new School("SUBJ-SCH2", "Other School")).getId();

                GradeLevel gl1 = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL10", "Grade 10"));
                GradeLevel gl2 = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL11", "Grade 11"));
                gradeLevel1Id = gl1.getId();
                gradeLevel2Id = gl2.getId();

                // Subjects in the caller's school.
                Subject math = subjectRepo.saveAndFlush(
                                new Subject(schoolId, gl1.getId(), "GEN-MATH", "Toán"));
                math.getId();
                subjectRepo.saveAndFlush(new Subject(schoolId, gl1.getId(), "GEN-PHYS", "Vật lý"));
                subjectRepo.saveAndFlush(new Subject(schoolId, gl2.getId(), "ADV-LIT", "Ngữ văn"));

                // Archived subject in the same school — must be excluded.
                Subject archived = new Subject(schoolId, gl1.getId(), "OLD", "Cũ");
                archived.setStatus(AcademicStatus.ARCHIVED);
                subjectRepo.saveAndFlush(archived);

                // Subject in a DIFFERENT school — must be excluded.
                GradeLevel otherGl = glRepo.saveAndFlush(new GradeLevel(otherSchoolId, "OGL", "Other GL"));
                subjectRepo.saveAndFlush(new Subject(otherSchoolId, otherGl.getId(), "XS", "Cross"));

                teacherRepo.saveAndFlush(new TeacherProfile(teacherUserId, schoolId, "TC-SUBJ"));
        }

        // ==================== 200 + school scope ====================

        @Test
        void listSubjects_returnsOnlyCallerSchoolActiveSubjects() throws Exception {
                mockMvc.perform(get("/api/subjects").with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                // 3 ACTIVE subjects in the caller's school (math, phys, lit) —
                                // archived + cross-school excluded.
                                .andExpect(jsonPath("$.items.length()").value(3))
                                .andExpect(jsonPath("$.items[0].id").exists())
                                .andExpect(jsonPath("$.items[0].code").exists())
                                .andExpect(jsonPath("$.items[0].name").exists())
                                .andExpect(jsonPath("$.items[0].gradeLevelId").exists());
        }

        @Test
        void listSubjects_crossSchoolSubjectExcluded() throws Exception {
                mockMvc.perform(get("/api/subjects").with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                // The cross-school subject code "XS" must never appear.
                                .andExpect(jsonPath("$.items[?(@.code == 'XS')]").isEmpty());
        }

        @Test
        void listSubjects_archivedExcluded() throws Exception {
                mockMvc.perform(get("/api/subjects").with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items[?(@.code == 'OLD')]").isEmpty());
        }

        // ==================== filters ====================

        @Test
        void listSubjects_searchByNameCaseInsensitive() throws Exception {
                mockMvc.perform(get("/api/subjects")
                                .param("search", "toán") // matches name "Toán"
                                .with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(1))
                                .andExpect(jsonPath("$.items[0].code").value("GEN-MATH"))
                                .andExpect(jsonPath("$.items[0].name").value("Toán"));
        }

        @Test
        void listSubjects_searchByCode() throws Exception {
                mockMvc.perform(get("/api/subjects")
                                .param("search", "phys")
                                .with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(1))
                                .andExpect(jsonPath("$.items[0].code").value("GEN-PHYS"));
        }

        @Test
        void listSubjects_filterByGradeLevel() throws Exception {
                mockMvc.perform(get("/api/subjects")
                                .param("gradeLevelId", gradeLevel2Id.toString())
                                .with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(1))
                                .andExpect(jsonPath("$.items[0].code").value("ADV-LIT"))
                                .andExpect(jsonPath("$.items[0].gradeLevelId").value(gradeLevel2Id.intValue()));
        }

        @Test
        void listSubjects_combinedSearchAndGradeLevel() throws Exception {
                mockMvc.perform(get("/api/subjects")
                                .param("search", "gen")
                                .param("gradeLevelId", gradeLevel1Id.toString())
                                .with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                // GEN-MATH + GEN-PHYS are in gl1 and contain "gen" in code.
                                .andExpect(jsonPath("$.items.length()").value(2));
        }

        @Test
        void listSubjects_emptyWhenNoMatch() throws Exception {
                mockMvc.perform(get("/api/subjects")
                                .param("search", "zzz-nothing")
                                .with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(0));
        }

        // ==================== authorization ====================

        @Test
        void listSubjects_unauthenticated_returns401() throws Exception {
                mockMvc.perform(get("/api/subjects"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        void listSubjects_noRoleOrPermission_returns403() throws Exception {
                User nobody = userRepo.saveAndFlush(
                                new User("subj-nobody", "sn@test.com", "hash", "Nobody"));
                mockMvc.perform(get("/api/subjects").with(jwtFor(nobody.getId())))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ACADEMIC_ACCESS_DENIED"));
        }

        @Test
        void listSubjects_studentRole_returns403() throws Exception {
                // STUDENT role does NOT include SUBJECT_READ.
                User student = userRepo.saveAndFlush(
                                new User("subj-student", "ss@test.com", "hash", "Subject Student"));
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", student.getId());

                mockMvc.perform(get("/api/subjects").with(jwtFor(student.getId())))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ACADEMIC_ACCESS_DENIED"));
        }

        @Test
        void listSubjects_permissionRevoked_returns403() throws Exception {
                revokePermission("SUBJECT_READ");
                mockMvc.perform(get("/api/subjects").with(jwtFor(teacherUserId)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ACADEMIC_ACCESS_DENIED"));
        }

        @Test
        void listSubjects_teacherRoleWithoutProfile_returns404() throws Exception {
                // Has TEACHER role (so SUBJECT_READ is effective) but no TeacherProfile row.
                User teacherNoProfile = userRepo.saveAndFlush(
                                new User("subj-no-tp", "sntp@test.com", "hash", "Teacher No TP"));
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherNoProfile.getId());

                mockMvc.perform(get("/api/subjects").with(jwtFor(teacherNoProfile.getId())))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.code").value("ACADEMIC_TEACHER_PROFILE_NOT_FOUND"));
        }

        // ==================== Helpers ====================

        private void revokePermission(String permCode) {
                jdbc.update("DELETE FROM role_permissions "
                                + "WHERE role_id = (SELECT id FROM roles WHERE code = 'TEACHER') "
                                + "AND permission_id = (SELECT id FROM permissions WHERE code = ?)",
                                permCode);
        }

        private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(Long userId) {
                return jwt().jwt(token -> token.subject(userId.toString()).claim("token_version", 0));
        }
}
