package com.hhtuann.backend.classroom;

import com.hhtuann.backend.classroom.application.ClassroomService;
import com.hhtuann.backend.classroom.dto.AddMembersRequest;
import com.hhtuann.backend.classroom.dto.AddMembersResponse;
import com.hhtuann.backend.classroom.dto.ClassroomDetailView;
import com.hhtuann.backend.classroom.dto.ClassroomResponse;
import com.hhtuann.backend.classroom.dto.CreateClassroomRequest;
import com.hhtuann.backend.classroom.dto.MyClassroomsResponse;
import com.hhtuann.backend.classroom.dto.UpdateClassroomRequest;
import com.hhtuann.backend.classroom.exception.ClassroomException;
import com.hhtuann.backend.question.dto.PageResponse;
import com.hhtuann.backend.classroom.dto.ClassroomMemberResponse;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the Classroom API (Classes Phase 1). Covers CRUD, bulk
 * add-members (partial success), remove, code conflict, cross-school rejection,
 * and cross-role authorization.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@Transactional
class ClassroomIntegrationTests {

        @Autowired
        private ClassroomService classroomService;
        @Autowired
        private JdbcTemplate jdbc;
        @Autowired
        private MutableClock clock;

        private long teacherUserId;
        private long schoolId;
        private long teacherProfileId;
        private long studentProfileId1;
        private long studentProfileId2;
        private long otherSchoolStudentProfileId;

        @BeforeEach
        void setUp() {
                clock.setInstant(Instant.parse("2026-07-10T08:00:00Z"));
                String tag = java.util.UUID.randomUUID().toString().substring(0, 6);

                teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('tchr" + tag + "','tchr" + tag + "@t.com','h','Teacher')");
                long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacherUserId + "," + tr + ")");
                schoolId = insert("INSERT INTO schools (code, name) VALUES ('SC" + tag + "','School')");
                long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
                insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl
                                + ",'M','Math')");
                teacherProfileId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                                + "VALUES (" + teacherUserId + "," + schoolId + ",'TC" + tag + "')");

                // Two students in the same school.
                studentProfileId1 = createStudent(schoolId, tag + "s1");
                studentProfileId2 = createStudent(schoolId, tag + "s2");

                // A student in a DIFFERENT school (for cross-school rejection).
                long otherSchool = insert("INSERT INTO schools (code, name) VALUES ('OS" + tag + "','Other')");
                otherSchoolStudentProfileId = createStudent(otherSchool, tag + "os");
        }

        // ============================================================
        // CREATE
        // ============================================================

        @Test
        void createClassroomReturnsResponse() {
                ClassroomResponse resp = classroomService.createClassroom(teacherUserId,
                                new CreateClassroomRequest("CLS-A", "Class A", "Description A"));
                assertThat(resp.id()).isNotNull();
                assertThat(resp.code()).isEqualTo("CLS-A");
                assertThat(resp.name()).isEqualTo("Class A");
                assertThat(resp.status()).isEqualTo("ACTIVE");
                assertThat(resp.memberCount()).isZero();
                assertThat(resp.ownerTeacherId()).isEqualTo(teacherProfileId);
        }

        @Test
        void createClassroomCodeConflictReturns409() {
                classroomService.createClassroom(teacherUserId, new CreateClassroomRequest("CLS-B", "B", null));
                assertThatThrownBy(() -> classroomService.createClassroom(teacherUserId,
                                new CreateClassroomRequest("cls-b", "B2", null))) // case-insensitive conflict
                                .isInstanceOf(ClassroomException.class)
                                .satisfies(e -> assertThat(((ClassroomException) e).getErrorCode().name())
                                                .isEqualTo("CLASSROOM_CODE_CONFLICT"));
        }

        // ============================================================
        // LIST MY
        // ============================================================

        @SuppressWarnings("null")
        @Test
        void listMyClassroomsReturnsOwnedOnly() {
                classroomService.createClassroom(teacherUserId, new CreateClassroomRequest("C1", "One", null));
                classroomService.createClassroom(teacherUserId, new CreateClassroomRequest("C2", "Two", null));
                MyClassroomsResponse resp = classroomService.listMyClassrooms(teacherUserId);
                assertThat(resp.items()).hasSize(2);
                assertThat(resp.items()).extracting(ClassroomResponse::code).contains("C1", "C2");
        }

        // ============================================================
        // GET DETAIL
        // ============================================================

        @Test
        void getClassroomDetailIncludesMembers() {
                long classroomId = classroomService.createClassroom(teacherUserId,
                                new CreateClassroomRequest("D1", "Detail", null)).id();
                classroomService.addMembers(teacherUserId, classroomId,
                                new AddMembersRequest(List.of(studentProfileId1, studentProfileId2)));

                ClassroomDetailView detail = classroomService.getClassroom(teacherUserId, classroomId);
                assertThat(detail.code()).isEqualTo("D1");
                assertThat(detail.memberCount()).isEqualTo(2);
                assertThat(detail.members()).hasSize(2);
        }

        // ============================================================
        // UPDATE
        // ============================================================

        @Test
        void updateClassroomChangesNameAndDescription() {
                long id = classroomService.createClassroom(teacherUserId,
                                new CreateClassroomRequest("U1", "Old", "Old desc")).id();
                ClassroomResponse resp = classroomService.updateClassroom(teacherUserId, id,
                                new UpdateClassroomRequest("New Name", "New desc"));
                assertThat(resp.name()).isEqualTo("New Name");
        }

        // ============================================================
        // ADD MEMBERS (bulk, partial success)
        // ============================================================

        @Test
        void addMembersPartialSuccess() {
                long classroomId = classroomService.createClassroom(teacherUserId,
                                new CreateClassroomRequest("M1", "Members", null)).id();
                // Add studentProfileId1 first.
                classroomService.addMembers(teacherUserId, classroomId,
                                new AddMembersRequest(List.of(studentProfileId1)));

                // Now add: studentProfileId1 (duplicate), studentProfileId2 (new),
                // otherSchoolStudentProfileId (cross-school invalid).
                AddMembersResponse resp = classroomService.addMembers(teacherUserId, classroomId,
                                new AddMembersRequest(List.of(studentProfileId1, studentProfileId2,
                                                otherSchoolStudentProfileId)));
                assertThat(resp.added()).isEqualTo(1); // only studentProfileId2
                assertThat(resp.duplicated()).containsExactly(studentProfileId1);
                assertThat(resp.invalid()).containsExactly(otherSchoolStudentProfileId);
        }

        // ============================================================
        // REMOVE MEMBER
        // ============================================================

        @Test
        void removeMemberDecreasesCount() {
                long classroomId = classroomService.createClassroom(teacherUserId,
                                new CreateClassroomRequest("R1", "Remove", null)).id();
                classroomService.addMembers(teacherUserId, classroomId,
                                new AddMembersRequest(List.of(studentProfileId1, studentProfileId2)));
                classroomService.removeMember(teacherUserId, classroomId, studentProfileId1);
                ClassroomDetailView detail = classroomService.getClassroom(teacherUserId, classroomId);
                assertThat(detail.memberCount()).isEqualTo(1);
        }

        // ============================================================
        // LIST MEMBERS (paginated)
        // ============================================================

        @Test
        void listMembersPaginated() {
                long classroomId = classroomService.createClassroom(teacherUserId,
                                new CreateClassroomRequest("P1", "Paged", null)).id();
                classroomService.addMembers(teacherUserId, classroomId,
                                new AddMembersRequest(List.of(studentProfileId1, studentProfileId2)));
                PageResponse<ClassroomMemberResponse> page = classroomService.listMembers(teacherUserId, classroomId, 0,
                                10, null);
                assertThat(page.items()).hasSize(2);
                assertThat(page.totalElements()).isEqualTo(2);
        }

        // ============================================================
        // AUTHORIZATION
        // ============================================================

        @Test
        void nonTeacherRoleDeniedCreate() {
                // studentProfileId1's user has STUDENT role only → no CLASSROOM_CREATE
                // permission.
                long studentUserId = jdbc.queryForObject(
                                "SELECT user_id FROM student_profiles WHERE id = " + studentProfileId1, Long.class);
                assertThatThrownBy(() -> classroomService.createClassroom(studentUserId,
                                new CreateClassroomRequest("X", "X", null)))
                                .isInstanceOf(ClassroomException.class);
        }

        @Test
        void accessDeniedForNonOwnedClassroom() {
                long classroomId = classroomService.createClassroom(teacherUserId,
                                new CreateClassroomRequest("O1", "Owned", null)).id();
                // Create a second teacher.
                long t2 = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('t2','t2@t.com','h','T2')");
                long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + t2 + "," + tr + ")");
                insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + t2 + "," + schoolId
                                + ",'TC2')");
                // T2 tries to access T1's classroom → access denied.
                assertThatThrownBy(() -> classroomService.getClassroom(t2, classroomId))
                                .isInstanceOf(ClassroomException.class)
                                .satisfies(e -> assertThat(((ClassroomException) e).getErrorCode().name())
                                                .isEqualTo("CLASSROOM_ACCESS_DENIED"));
        }

        // ============================================================
        // Helpers
        // ============================================================

        private long createStudent(long school, String suffix) {
                long u = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('stu" + suffix + "','stu" + suffix + "@t.com','h','Student " + suffix
                                + "')");
                long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + sr + ")");
                return insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                                + "VALUES (" + u + "," + school + ",'SC" + suffix + "')");
        }

        private long insert(String sql) {
                return jdbc.queryForObject(sql + " RETURNING id", Long.class);
        }
}
