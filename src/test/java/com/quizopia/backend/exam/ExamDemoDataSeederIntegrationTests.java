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
 * Integration tests for {@link DemoDataSeeder#seedExamPurposes} — verifies the
 * 4 default exam purposes are created idempotently for the demo school when
 * the demo flag is enabled. Uses a dedicated Spring context with
 * {@code quizopia.demo.data.enabled=true} so the seeder runs at startup.
 */
@SpringBootTest(properties = "quizopia.demo.data.enabled=true")
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
class ExamDemoDataSeederIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private ExamPurposeRepository purposeRepo;
    @Autowired private DemoDataSeeder seeder;

    @Test
    @Transactional
    void fourPurposesSeededForDemoSchool() {
        // DemoDataSeeder ran at startup → demo school + purposes should exist
        Long demoSchoolId = schoolRepo.findByCodeIgnoreCase(DemoDataSeeder.DEMO_SCHOOL_CODE)
                .orElseThrow().getId();

        var purposes = purposeRepo.findAllBySchoolIdOrderByPositionAsc(demoSchoolId);
        assertThat(purposes).hasSize(4);
        assertThat(purposes).extracting(p -> p.getCode()).containsExactly("MIDTERM", "FINAL", "QUIZ", "PRACTICE");
        assertThat(purposes).extracting(p -> p.getTitle()).containsExactly("Giữa kỳ", "Cuối kỳ", "Bài kiểm tra", "Luyện tập");
        assertThat(purposes).extracting(p -> p.getPosition()).containsExactly(0, 1, 2, 3);
    }

    @Test
    @Transactional
    void seederIdempotentRunAgainDoesNotDuplicate() {
        // Run seeder again (it already ran at startup)
        seeder.run(new org.springframework.boot.DefaultApplicationArguments());

        Long demoSchoolId = schoolRepo.findByCodeIgnoreCase(DemoDataSeeder.DEMO_SCHOOL_CODE)
                .orElseThrow().getId();
        var purposes = purposeRepo.findAllBySchoolIdOrderByPositionAsc(demoSchoolId);
        assertThat(purposes).hasSize(4); // Still 4, no duplicates
    }

    @Test
    @Transactional
    void noPurposesForNonDemoSchools() {
        // Create a non-demo school — it should NOT have purposes seeded by DemoDataSeeder
        jdbc.update("INSERT INTO schools (code, name) VALUES ('NON-DEMO','Non Demo')");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_purposes p JOIN schools s ON s.id = p.school_id WHERE s.code = 'NON-DEMO'",
                Integer.class);
        assertThat(count).isZero();
    }
}
