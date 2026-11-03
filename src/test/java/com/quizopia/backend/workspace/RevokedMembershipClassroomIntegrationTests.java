package com.quizopia.backend.workspace;

import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.School;
import com.quizopia.backend.academic.domain.model.StudentProfile;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.domain.model.TeacherProfile;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SchoolRepository;
import com.quizopia.backend.academic.repository.StudentProfileRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.academic.repository.TeacherProfileRepository;
import com.quizopia.backend.classroom.application.ClassroomService;
import com.quizopia.backend.classroom.dto.CreateClassroomRequest;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-2 reproduction (issue #4): a CLASSROOM creator whose organization
 * workspace membership is revoked must lose management access to the
 * classroom they created.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RevokedMembershipClassroomIntegrationTests {

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
        private StudentProfileRepository studentRepo;
        @Autowired
        private ClassroomService classroomService;
        @Autowired
        private JdbcTemplate jdbc;
        @jakarta.persistence.PersistenceContext
        private jakarta.persistence.EntityManager em;

        private Long teacherUserId;
        private Long schoolId;

        @BeforeEach
        void setUp() {
                User teacher = userRepo.saveAndFlush(
                                new User("rv-cl-teacher", "rvcl@test.com", "hash", "RV CL Teacher"));
                teacherUserId = teacher.getId();
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);
                School school = schoolRepo.saveAndFlush(new School("RV-CL-SCH", "RV CL School"));
                schoolId = school.getId();
                GradeLevel gl = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL", "Grade"));
                subjectRepo.saveAndFlush(new Subject(schoolId, gl.getId(), "SUB", "Subject"));
                teacherRepo.saveAndFlush(new TeacherProfile(teacherUserId, schoolId, "TC-RVCL"));
        }

        private Long createClassroom() {
                return classroomService.createClassroom(teacherUserId,
                                new CreateClassroomRequest("RV-CL", "Revoked Class", null)).id();
        }

        private void setMembershipStatus(String status) {
                jdbc.update("UPDATE workspace_memberships SET status = ? "
                                + "WHERE workspace_id = (SELECT workspace_id FROM classrooms WHERE code = 'RV-CL' "
                                + "AND school_id = " + schoolId + " LIMIT 1) AND user_id = " + teacherUserId, status);
                // Detach the pre-revocation (ACTIVE) membership so the service
                // re-reads it — production revocation happens in another session.
                em.clear();
        }

        @Test
        void removedCreatorCannotReadClassroom() {
                Long classroomId = createClassroom();
                setMembershipStatus("REMOVED");
                assertThatThrownBy(() -> classroomService.getClassroom(teacherUserId, classroomId))
                                .isInstanceOf(ClassroomException.class);
        }

        @Test
        void removedCreatorCannotUpdateClassroom() {
                Long classroomId = createClassroom();
                setMembershipStatus("REMOVED");
                assertThatThrownBy(() -> classroomService.updateClassroom(teacherUserId, classroomId,
                                new com.quizopia.backend.classroom.dto.UpdateClassroomRequest("New Name", null)))
                                .isInstanceOf(ClassroomException.class);
        }

        @Test
        void removedCreatorDoesNotSeeClassroomInMyList() {
                createClassroom();
                setMembershipStatus("REMOVED");
                var list = classroomService.listMyClassrooms(teacherUserId);
                assertThat(list.items()).isEmpty();
        }

        @Test
        void activeCreatorKeepsAccess() {
                Long classroomId = createClassroom();
                setMembershipStatus("ACTIVE");
                classroomService.getClassroom(teacherUserId, classroomId); // no exception
                var list = classroomService.listMyClassrooms(teacherUserId);
                assertThat(list.items()).isNotEmpty();
        }
}
