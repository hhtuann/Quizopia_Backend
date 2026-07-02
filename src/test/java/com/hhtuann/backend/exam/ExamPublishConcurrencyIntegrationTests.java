package com.hhtuann.backend.exam;

import com.hhtuann.backend.exam.application.ExamService;
import com.hhtuann.backend.exam.dto.PublishExamRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.hhtuann.backend.exam.exception.ExamException;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test for POST /api/exams/{examId}/publish (A3.2-2C): two concurrent publishes
 * of the same DRAFT must yield exactly one success and one EXAM_PUBLISH_CONFLICT, with a
 * single DRAFT→PUBLISHED transition and no partial snapshot. Deliberately NOT @Transactional
 * so each publish runs in its own committed transaction and the pessimistic exam lock is
 * exercised for real. Uses unique identifiers to avoid cross-test DB pollution.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
// Non-transactional: each publish runs in its own committed tx. @DirtiesContext discards this
// context (and its Testcontainers DB) after the class so the committed data never pollutes the
// shared DB used by other test classes (e.g. QuestionImportServiceIntegrationTests cleanup).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExamPublishConcurrencyIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;

    private long teacherUserId;
    private long examId;

    @BeforeEach
    void setUp() {
        // Fixtures committed (no surrounding transaction).
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('cx','cx@t','h','CX')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        long schoolId = insert("INSERT INTO schools (code, name) VALUES ('CXC','CX School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'CXC')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp + ",'CXB','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q1','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        examId = examService.createExam(teacherUserId,
                new com.hhtuann.backend.exam.dto.CreateExamRequest(subjectId, null, "CXE", "T", null)).id();
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(1, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(q, 0, null))))));
    }

    @Test
    void concurrentPublishOneSuccessOneConflict() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> task = () -> {
            start.await();
            try {
                examService.publishExam(teacherUserId, examId, new PublishExamRequest(null));
                return "OK";
            } catch (ExamException e) {
                return e.getErrorCode().name();
            }
        };
        Future<String> f1 = pool.submit(task);
        Future<String> f2 = pool.submit(task);
        start.countDown(); // release both threads near-simultaneously
        String r1 = f1.get(30, TimeUnit.SECONDS);
        String r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Exactly one success and one EXAM_PUBLISH_CONFLICT.
        long ok = List.of(r1, r2).stream().filter("OK"::equals).count();
        long conflict = List.of(r1, r2).stream().filter("EXAM_PUBLISH_CONFLICT"::equals).count();
        assertThat(ok).as("exactly one publish succeeded").isEqualTo(1L);
        assertThat(conflict).as("exactly one publish conflicted").isEqualTo(1L);

        // Single DRAFT->PUBLISHED transition; no partial snapshot (1 PUBLISHED, 0 DRAFT).
        Integer published = jdbc.queryForObject("SELECT COUNT(*) FROM exam_versions WHERE exam_id=? AND status='PUBLISHED'", Integer.class, examId);
        Integer draft = jdbc.queryForObject("SELECT COUNT(*) FROM exam_versions WHERE exam_id=? AND status='DRAFT'", Integer.class, examId);
        assertThat(published).isEqualTo(1);
        assertThat(draft).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId)).isEqualTo("READY");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
