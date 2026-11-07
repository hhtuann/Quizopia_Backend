package com.quizopia.backend.exam;

import com.quizopia.backend.academic.application.DemoDataSeeder;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving that when {@code quizopia.demo.data.enabled=false},
 * {@link DemoDataSeeder} does NOT create the demo accounts or the DEMO-CLASS
 * classroom. (The global academic catalog comes from the V24 migration and is
 * present regardless — the seeder's disabled path is about demo DATA, not the
 * catalog.) Uses a dedicated Spring context with the demo flag disabled.
 */
@SpringBootTest(properties = "quizopia.demo.data.enabled=false")
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
class ExamDemoDataSeederDisabledIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClassroomRepository classroomRepo;

    @Test
    @Transactional
    void demoUsersNotCreatedWhenDisabled() {
        Integer demoUsers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username IN "
                        + "('demo-teacher','demo-student-1','demo-student-2','demo-student-3')",
                Integer.class);
        assertThat(demoUsers).isZero();
    }

    @Test
    @Transactional
    void demoClassroomNotCreatedWhenDisabled() {
        assertThat(classroomRepo.findAll().stream()
                .noneMatch(c -> DemoDataSeeder.DEMO_CLASSROOM_CODE.equalsIgnoreCase(c.getCode())))
                .as("DEMO-CLASS must not exist when the demo flag is off").isTrue();
        Integer members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_members", Integer.class);
        assertThat(members).isZero();
    }
}
