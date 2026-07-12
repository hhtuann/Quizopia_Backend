package com.quizopia.backend.exam;

import com.quizopia.backend.academic.application.DemoDataSeeder;
import com.quizopia.backend.academic.repository.SchoolRepository;
import com.quizopia.backend.exam.repository.ExamPurposeRepository;
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
 * {@link DemoDataSeeder} does NOT create the demo school or seed exam purposes.
 * Uses a dedicated Spring context with the demo flag disabled.
 *
 * <p>V8 migration seed (purpose seed for existing schools) does NOT interfere:
 * V8 runs at Flyway time before any schools exist on a clean DB, so it inserts
 * 0 rows. This test verifies the DemoDataSeeder path specifically.
 */
@SpringBootTest(properties = "quizopia.demo.data.enabled=false")
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
class ExamDemoDataSeederDisabledIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private ExamPurposeRepository purposeRepo;

    @Test
    @Transactional
    void demoSchoolNotCreatedWhenDisabled() {
        // With demo disabled, DemoDataSeeder does not run → no demo school
        assertThat(schoolRepo.findByCodeIgnoreCase(DemoDataSeeder.DEMO_SCHOOL_CODE))
                .isEmpty();
    }

    @Test
    @Transactional
    void noExamPurposesCreatedWhenDisabled() {
        // No demo school → no purposes from DemoDataSeeder
        // V8 migration also seeds 0 (no schools at migration time)
        Integer purposeCount = jdbc.queryForObject("SELECT COUNT(*) FROM exam_purposes", Integer.class);
        assertThat(purposeCount).isZero();
    }
}
