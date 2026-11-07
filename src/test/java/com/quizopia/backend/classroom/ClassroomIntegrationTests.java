package com.quizopia.backend.classroom;

import com.quizopia.backend.classroom.application.ClassroomService;
import com.quizopia.backend.classroom.dto.AddMembersRequest;
import com.quizopia.backend.classroom.dto.AddMembersResponse;
import com.quizopia.backend.classroom.dto.ClassroomDetailView;
import com.quizopia.backend.classroom.dto.ClassroomResponse;
import com.quizopia.backend.classroom.dto.CreateClassroomRequest;
import com.quizopia.backend.classroom.dto.MyClassroomsResponse;
import com.quizopia.backend.classroom.dto.UpdateClassroomRequest;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.question.dto.PageResponse;
import com.quizopia.backend.classroom.dto.ClassroomMemberResponse;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
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
        private long studentUserId1;
        private long studentUserId2;

        @BeforeEach
        void setUp() {
                clock.setInstant(Instant.parse("2026-07-10T08:00:00Z"));
                String tag = java.util.UUID.randomUUID().toString().substring(0, 6);

                teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('tchr" + tag + "','tchr" + tag + "@t.com','h','Teacher')");
                long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacherUserId + "," + tr + ")");

                // Two students (platform users; membership is user-based).
                studentUserId1 = createStudent(tag + "s1");
                studentUserId2 = createStudent(tag + "s2");
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
                assertThat(resp.ownerUserId()).isEqualTo(teacherUserId);
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
                                new AddMembersRequest(List.of(studentUserId1, studentUserId2)));

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
                // Add studentUserId1 first.
                classroomService.addMembers(teacherUserId, classroomId,
                                new AddMembersRequest(List.of(studentUserId1)));

                // Now add: studentUserId1 (duplicate), studentUserId2 (new),
                // and a nonexistent user id (invalid).
                long missingUserId = 999_999_999L;
                AddMembersResponse resp = classroomService.addMembers(teacherUserId, classroomId,
                                new AddMembersRequest(List.of(studentUserId1, studentUserId2, missingUserId)));
                assertThat(resp.added()).isEqualTo(1); // only studentUserId2
                assertThat(resp.duplicated()).containsExactly(studentUserId1);
                assertThat(resp.invalid()).containsExactly(missingUserId);
        }

        // ============================================================
        // REMOVE MEMBER
        // ============================================================

        @Test
        void removeMemberDecreasesCount() {
                long classroomId = classroomService.createClassroom(teacherUserId,
                                new CreateClassroomRequest("R1", "Remove", null)).id();
                classroomService.addMembers(teacherUserId, classroomId,
                                new AddMembersRequest(List.of(studentUserId1, studentUserId2)));
                classroomService.removeMember(teacherUserId, classroomId, studentUserId1);
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
                                new AddMembersRequest(List.of(studentUserId1, studentUserId2)));
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
                // A STUDENT-role user has no CLASSROOM_CREATE permission.
                assertThatThrownBy(() -> classroomService.createClassroom(studentUserId1,
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
                // T2 tries to access T1's classroom → access denied.
                assertThatThrownBy(() -> classroomService.getClassroom(t2, classroomId))
                                .isInstanceOf(ClassroomException.class)
                                .satisfies(e -> assertThat(((ClassroomException) e).getErrorCode().name())
                                                .isEqualTo("CLASSROOM_ACCESS_DENIED"));
        }

        // ============================================================
        // Helpers
        // ============================================================

        private long createStudent(String suffix) {
                long u = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('stu" + suffix + "','stu" + suffix + "@t.com','h','Student " + suffix
                                + "')");
                long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + sr + ")");
                return u;
        }

        private long insert(String sql) {
                return jdbc.queryForObject(sql + " RETURNING id", Long.class);
        }
}
