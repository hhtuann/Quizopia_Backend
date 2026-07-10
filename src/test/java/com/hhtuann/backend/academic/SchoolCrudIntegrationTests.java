package com.hhtuann.backend.academic;

import com.hhtuann.backend.academic.application.AcademicService;
import com.hhtuann.backend.academic.dto.CreateSchoolRequest;
import com.hhtuann.backend.academic.dto.SchoolListResponse;
import com.hhtuann.backend.academic.dto.SchoolResponse;
import com.hhtuann.backend.academic.dto.UpdateSchoolRequest;
import com.hhtuann.backend.academic.exception.AcademicException;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class SchoolCrudIntegrationTests {

    @Autowired private AcademicService academicService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void systemAdminListsAllSchools() {
        long sysAdmin = createUser("SYSTEM_ADMIN");
        // Create 2 schools.
        academicService.createSchool(sysAdmin, new CreateSchoolRequest("SCH-A", "School A", null));
        academicService.createSchool(sysAdmin, new CreateSchoolRequest("SCH-B", "School B", null));
        SchoolListResponse resp = academicService.listSchools(sysAdmin);
        assertThat(resp.items()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void teacherListsOnlyTheirSchool() {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        long schoolId = insert("INSERT INTO schools (code, name) VALUES ('TS" + tag + "','Teacher School')");
        long teacher = createUser("TEACHER");
        insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacher + "," + schoolId + ",'TC" + tag + "')");

        SchoolListResponse resp = academicService.listSchools(teacher);
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).id()).isEqualTo(schoolId);
    }

    @Test
    void createSchoolReturnsResponse() {
        long sysAdmin = createUser("SYSTEM_ADMIN");
        SchoolResponse resp = academicService.createSchool(sysAdmin,
                new CreateSchoolRequest("NEW-SCH", "New School", "123 Main St"));
        assertThat(resp.id()).isNotNull();
        assertThat(resp.code()).isEqualTo("NEW-SCH");
        assertThat(resp.name()).isEqualTo("New School");
        assertThat(resp.address()).isEqualTo("123 Main St");
        assertThat(resp.status()).isEqualTo("ACTIVE");
    }

    @Test
    void createSchoolCodeConflictReturns409() {
        long sysAdmin = createUser("SYSTEM_ADMIN");
        academicService.createSchool(sysAdmin, new CreateSchoolRequest("CF-SCH", "Conflict", null));
        assertThatThrownBy(() -> academicService.createSchool(sysAdmin,
                new CreateSchoolRequest("cf-sch", "Conflict 2", null))) // case-insensitive
                .isInstanceOf(AcademicException.class)
                .satisfies(e -> assertThat(((AcademicException) e).getErrorCode().name())
                        .isEqualTo("ACADEMIC_SCHOOL_CODE_CONFLICT"));
    }

    @Test
    void nonAdminCannotCreateSchool() {
        long teacher = createUser("TEACHER");
        assertThatThrownBy(() -> academicService.createSchool(teacher,
                new CreateSchoolRequest("DENY", "Denied", null)))
                .isInstanceOf(AcademicException.class)
                .satisfies(e -> assertThat(((AcademicException) e).getErrorCode().name())
                        .isEqualTo("ACADEMIC_ACCESS_DENIED"));
    }

    @Test
    void updateSchoolChangesNameAndAddress() {
        long sysAdmin = createUser("SYSTEM_ADMIN");
        long schoolId = insert("INSERT INTO schools (code, name) VALUES ('UP" + UUID.randomUUID().toString().substring(0,6) + "','Old')");
        SchoolResponse resp = academicService.updateSchool(sysAdmin, schoolId,
                new UpdateSchoolRequest("New Name", "New Address"));
        assertThat(resp.name()).isEqualTo("New Name");
        assertThat(resp.address()).isEqualTo("New Address");
    }

    private long createUser(String roleCode) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long user = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('u" + tag + "','u" + tag + "@t.com','h','User')");
        long role = jdbc.queryForObject("SELECT id FROM roles WHERE code='" + roleCode + "'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + user + "," + role + ")");
        return user;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
