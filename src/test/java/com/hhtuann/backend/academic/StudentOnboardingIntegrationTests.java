package com.hhtuann.backend.academic;

import com.hhtuann.backend.academic.application.StudentOnboardingService;
import com.hhtuann.backend.academic.dto.PendingStudentItem;
import com.hhtuann.backend.academic.dto.StudentProfileResponse;
import com.hhtuann.backend.academic.dto.StudentSearchResponse;
import com.hhtuann.backend.academic.exception.AcademicException;
import com.hhtuann.backend.question.dto.PageResponse;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the Student Onboarding API (V11): pending-students list,
 * assign-school with auto-generated student_code, and school-scoped search.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class StudentOnboardingIntegrationTests {

    @Autowired private StudentOnboardingService onboardingService;
    @Autowired private JdbcTemplate jdbc;

    private long adminUserId;
    private long schoolId;
    private long teacherUserId;
    private long pendingStudentUserId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-10T08:00:00Z");
        String tag = UUID.randomUUID().toString().substring(0, 6);

        // School.
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('SC" + tag + "','Test School')");

        // ACADEMIC_ADMIN user (has USER_CREATE, STUDENT_PROFILE_CREATE via V11 grants).
        adminUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('adm" + tag + "','adm" + tag + "@t.com','h','Admin')");
        long aaRole = jdbc.queryForObject("SELECT id FROM roles WHERE code='ACADEMIC_ADMIN'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + adminUserId + "," + aaRole + ")");

        // TEACHER user (has STUDENT_PROFILE_READ via V3 grants; for search tests).
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('tch" + tag + "','tch" + tag + "@t.com','h','Teacher')");
        long trRole = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacherUserId + "," + trRole + ")");
        insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + teacherUserId + "," + schoolId + ",'TC" + tag + "')");

        // PENDING student (registered, has STUDENT role, NO profile).
        pendingStudentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('pend" + tag + "','pend" + tag + "@t.com','h','Pending Student')");
        long srRole = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + pendingStudentUserId + "," + srRole + ")");

        // An ASSIGNED student (already has a profile → not pending).
        long assignedUser = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('asg" + tag + "','asg" + tag + "@t.com','h','Assigned Student')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + assignedUser + "," + srRole + ")");
        insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                + "VALUES (" + assignedUser + "," + schoolId + ",'ASG001')");
    }

    // ============================================================
    // PENDING STUDENTS LIST
    // ============================================================

    @Test
    void listPendingReturnsStudentsWithoutProfiles() {
        PageResponse<PendingStudentItem> page = onboardingService.listPendingStudents(adminUserId, null, 0, 20);
        assertThat(page.items()).isNotEmpty();
        assertThat(page.items()).anySatisfy(i -> assertThat(i.userId()).isEqualTo(pendingStudentUserId));
        // The assigned student should NOT appear.
        assertThat(page.items()).noneSatisfy(i -> assertThat(i.username()).startsWith("asg"));
    }

    @Test
    void listPendingSearchByUsername() {
        PageResponse<PendingStudentItem> page = onboardingService.listPendingStudents(adminUserId, "pend", 0, 20);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).userId()).isEqualTo(pendingStudentUserId);
    }

    // ============================================================
    // ASSIGN STUDENT TO SCHOOL
    // ============================================================

    @Test
    void assignStudentCreatesProfileWithAutoCode() {
        StudentProfileResponse resp = onboardingService.assignStudentToSchool(adminUserId, pendingStudentUserId, schoolId);
        assertThat(resp.id()).isNotNull();
        assertThat(resp.studentCode()).isNotBlank();
        assertThat(resp.schoolId()).isEqualTo(schoolId);
        assertThat(resp.userId()).isEqualTo(pendingStudentUserId);
        assertThat(resp.enrollmentStatus()).isEqualTo("ACTIVE");
        // Code format: prefix (3 alpha from school code) + 4-digit zero-padded counter.
        assertThat(resp.studentCode()).matches("[A-Z]{1,3}\\d{4}");
    }

    @Test
    void assignStudentTwiceReturns409() {
        onboardingService.assignStudentToSchool(adminUserId, pendingStudentUserId, schoolId);
        assertThatThrownBy(() -> onboardingService.assignStudentToSchool(adminUserId, pendingStudentUserId, schoolId))
                .isInstanceOf(AcademicException.class)
                .satisfies(e -> assertThat(((AcademicException) e).getErrorCode().name())
                        .isEqualTo("ACADEMIC_STUDENT_ALREADY_ASSIGNED"));
    }

    @Test
    void assignNonStudentReturns404() {
        // Admin user is NOT a student → 404.
        assertThatThrownBy(() -> onboardingService.assignStudentToSchool(adminUserId, adminUserId, schoolId))
                .isInstanceOf(AcademicException.class)
                .satisfies(e -> assertThat(((AcademicException) e).getErrorCode().name())
                        .isEqualTo("ACADEMIC_STUDENT_NOT_FOUND"));
    }

    // ============================================================
    // STUDENT SEARCH (TEACHER)
    // ============================================================

    @Test
    void searchReturnsSchoolScopedResults() {
        StudentSearchResponse resp = onboardingService.searchStudents(teacherUserId, "ASG");
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).studentCode()).isEqualTo("ASG001");
    }

    @Test
    void searchExcludesOtherSchoolStudents() {
        // Create a student in a different school.
        long otherSchool = insert("INSERT INTO schools (code, name) VALUES ('OS','Other')");
        long otherStudent = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('other','other@t.com','h','Other')");
        insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                + "VALUES (" + otherStudent + "," + otherSchool + ",'OTH001')");

        // Search by "OTH" → should NOT find it (different school).
        StudentSearchResponse resp = onboardingService.searchStudents(teacherUserId, "OTH");
        assertThat(resp.items()).isEmpty();
    }

    @Test
    void searchEmptyQueryReturnsAllInSchool() {
        StudentSearchResponse resp = onboardingService.searchStudents(teacherUserId, null);
        assertThat(resp.items()).hasSize(1); // only the assigned student
    }

    // ============================================================
    // AUTHORIZATION
    // ============================================================

    @Test
    void teacherCannotAssignStudent() {
        assertThatThrownBy(() -> onboardingService.assignStudentToSchool(teacherUserId, pendingStudentUserId, schoolId))
                .isInstanceOf(AcademicException.class)
                .satisfies(e -> assertThat(((AcademicException) e).getErrorCode().name())
                        .isEqualTo("ACADEMIC_ACCESS_DENIED"));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
