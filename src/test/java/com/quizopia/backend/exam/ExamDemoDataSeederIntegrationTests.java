package com.quizopia.backend.exam;

import com.quizopia.backend.academic.application.DemoDataSeeder;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.exam.repository.ExamPurposeRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.security.password.PasswordHasher;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the user-centric {@link DemoDataSeeder} — verifies the
 * global academic catalog, demo accounts (demo-teacher + demo-student-1..3)
 * and the DEMO-CLASS classroom owned by the demo teacher (students as
 * members) are ensured idempotently when the demo flag is enabled. Uses a
 * dedicated Spring context with {@code quizopia.demo.data.enabled=true} so the
 * seeder runs at startup.
 */
@SpringBootTest(properties = "quizopia.demo.data.enabled=true")
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
class ExamDemoDataSeederIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private GradeLevelRepository gradeLevelRepo;
    @Autowired private ExamPurposeRepository purposeRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private ClassroomRepository classroomRepo;
    @Autowired private DemoDataSeeder seeder;
    @Autowired private PasswordHasher passwordHasher;

    @Test
    @Transactional
    void globalCatalogEnsured() {
        // Grade levels + purposes form the global (school-free) catalog.
        for (String code : List.of("G10", "G11", "G12")) {
            assertThat(gradeLevelRepo.findByCodeIgnoreCase(code)).as("grade level %s", code).isPresent();
        }
        var purposes = purposeRepo.findAllByOrderByPositionAsc();
        assertThat(purposes).extracting(p -> p.getCode())
                .containsExactly("MIDTERM", "FINAL", "QUIZ", "PRACTICE");
    }

    @Test
    @Transactional
    void demoAccountsSeededWithRolesAndPassword() {
        Long teacherId = userRepo.findByUsernameIgnoreCase("demo-teacher")
                .orElseThrow().getId();
        assertThat(activeRoleCodes(teacherId)).containsExactly("TEACHER");

        for (int i = 1; i <= 3; i++) {
            final int idx = i;
            Long studentId = userRepo.findByUsernameIgnoreCase("demo-student-" + idx)
                    .orElseThrow(() -> new AssertionError("demo-student-" + idx + " missing")).getId();
            assertThat(activeRoleCodes(studentId)).as("demo-student-%d role", idx).containsExactly("STUDENT");
        }

        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE username = 'demo-teacher'", String.class);
        assertThat(passwordHasher.matches(DemoDataSeeder.DEMO_PASSWORD, hash))
                .as("demo accounts share the documented demo password").isTrue();
    }

    @Test
    @Transactional
    void demoClassroomOwnedByDemoTeacherWithStudentMembers() {
        Long teacherId = userRepo.findByUsernameIgnoreCase("demo-teacher").orElseThrow().getId();
        var classroom = classroomRepo
                .findByOwnerUserIdAndCodeIgnoreCase(teacherId, DemoDataSeeder.DEMO_CLASSROOM_CODE)
                .orElseThrow(() -> new AssertionError("DEMO-CLASS classroom missing"));

        assertThat(classroom.getOwnerUserId()).isEqualTo(teacherId);

        List<Long> memberUserIds = jdbc.queryForList(
                "SELECT cm.user_id FROM classroom_members cm WHERE cm.classroom_id = ? ORDER BY cm.user_id",
                Long.class, classroom.getId());
        assertThat(memberUserIds).hasSize(3);
        for (int i = 1; i <= 3; i++) {
            Long studentId = userRepo.findByUsernameIgnoreCase("demo-student-" + i).orElseThrow().getId();
            assertThat(memberUserIds).as("demo-student-%d is a member", i).contains(studentId);
        }
    }

    @Test
    @Transactional
    void seederIdempotentRunAgainDoesNotDuplicate() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username IN ('demo-teacher','demo-student-1','demo-student-2','demo-student-3')",
                Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM classrooms WHERE code = ?", Integer.class,
                DemoDataSeeder.DEMO_CLASSROOM_CODE)).isEqualTo(1);
        Long classroomId = jdbc.queryForObject(
                "SELECT id FROM classrooms WHERE code = ?", Long.class, DemoDataSeeder.DEMO_CLASSROOM_CODE);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_members WHERE classroom_id = ?", Integer.class, classroomId))
                .isEqualTo(3);
        assertThat(purposeRepo.findAllByOrderByPositionAsc()).hasSize(4); // still 4, no duplicates
    }

    private List<String> activeRoleCodes(Long userId) {
        return jdbc.queryForList(
                "SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                        + "WHERE ur.user_id = ? AND (ur.expires_at IS NULL OR ur.expires_at > now())",
                String.class, userId);
    }
}
