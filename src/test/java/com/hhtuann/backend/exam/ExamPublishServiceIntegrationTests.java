package com.hhtuann.backend.exam;

import com.hhtuann.backend.exam.application.ExamService;
import com.hhtuann.backend.exam.domain.model.ExamVersionStatus;
import com.hhtuann.backend.exam.dto.PublishedExamSummary;
import com.hhtuann.backend.exam.dto.PublishExamRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.hhtuann.backend.exam.exception.ExamErrorCode;
import com.hhtuann.backend.exam.exception.ExamException;
import com.hhtuann.backend.exam.repository.ExamVersionRepository;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level integration tests for POST /api/exams/{examId}/publish (A3.2-2C). Real
 * PostgreSQL 17 Testcontainers. Covers pinned refresh, 4-type validation, numeric preservation,
 * totals, state transition, publish-twice conflict, rollback, and no per-source SELECT growth.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamPublishServiceIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;
    @Autowired private ExamVersionRepository versionRepo;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private long teacherUserId;
    private long schoolId;
    private long subjectId;
    private long teacherProfileId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('pt','pt@t','h','PT')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('PS','Publish School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        teacherProfileId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'PTC')");
    }

    // -- Test group: success with 4 types; totals; state --

    @Test
    void publishAllFourTypesSuccess() {
        long bank = newBank();
        long single = srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null);
        long multi = srcQuestion(bank, "m", "MULTIPLE_CHOICE", 4, 2, null);
        long tf = srcQuestion(bank, "t", "TRUE_FALSE_MATRIX", 4, 2, null);
        long num = srcQuestion(bank, "n", "NUMERIC_FILL", 0, 0, numericAk("2.50"));
        Long examId = createExam("E1");
        composeDraft(examId, List.of(single, multi, tf, num));

        PublishedExamSummary r = examService.publishExam(teacherUserId, examId, new PublishExamRequest(null));

        assertThat(r.examId()).isEqualTo(examId);
        assertThat(r.status()).isEqualTo("PUBLISHED");
        assertThat(r.versionNumber()).isEqualTo(1);
        assertThat(r.publishedAt()).isNotNull();
        assertThat(r.questionCount()).isEqualTo(4);
        assertThat(r.totalPoints()).isEqualByComparingTo("4.00"); // 4 × default 1.00
        assertThat(r.durationMinutes()).isEqualTo(60);
        // Exam READY + currentVersionNumber = 1.
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT current_version_number FROM exams WHERE id=?", Integer.class, examId)).isEqualTo(1);
    }

    @Test
    void noNewDraftAfterPublish() {
        long bank = newBank();
        Long examId = createExam("E2");
        composeDraft(examId, List.of(srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null)));
        examService.publishExam(teacherUserId, examId, new PublishExamRequest(null));
        // Exactly one version (PUBLISHED v1); no new DRAFT auto-created.
        assertThat(versionRepo.findAllByExamIdOrderByVersionNumberDesc(examId)).hasSize(1);
        assertThat(versionRepo.existsByExamIdAndStatus(examId, ExamVersionStatus.DRAFT)).isFalse();
    }

    // -- Test group: empty composition / empty section rejected --

    @Test
    void emptyCompositionRejected() {
        Long examId = createExam("E3");
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(1, null, null, List.of()));
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    @Test
    void emptySectionRejected() {
        long bank = newBank();
        Long examId = createExam("E4");
        // One section with zero questions.
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(1, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, List.of()))));
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    // -- Test group: invalid type shapes rejected --

    @Test
    void invalidTypeShapesRejected() {
        long bank = newBank();
        // SINGLE 3 options -> reject
        assertPublishRejects(bank, srcQuestion(bank, "s3", "SINGLE_CHOICE", 3, 1, null));
        // SINGLE 2 correct -> reject
        assertPublishRejects(bank, srcQuestion(bank, "s2", "SINGLE_CHOICE", 4, 2, null));
        // MULTIPLE 1 correct -> reject
        assertPublishRejects(bank, srcQuestion(bank, "m1", "MULTIPLE_CHOICE", 4, 1, null));
        // TF 3 options -> reject
        assertPublishRejects(bank, srcQuestion(bank, "t3", "TRUE_FALSE_MATRIX", 3, 1, null));
        // NUMERIC with 1 option -> reject
        assertPublishRejects(bank, srcQuestion(bank, "n1", "NUMERIC_FILL", 1, 0, numericAk("2.50")));
    }

    // -- Test group: numeric preservation --

    @Test
    void numericAnswerKeyPreservedVerbatim() {
        long bank = newBank();
        long q1 = srcQuestion(bank, "n1", "NUMERIC_FILL", 0, 0, numericAk("2.50"));
        long q2 = srcQuestion(bank, "n2", "NUMERIC_FILL", 0, 0, numericAk("02.5"));
        Long examId = createExam("E5");
        composeDraft(examId, List.of(q1, q2));
        examService.publishExam(teacherUserId, examId, new PublishExamRequest(null));
        // The published exam_questions keep the answerKey verbatim (no trim/normalize/float).
        String ak1 = jdbc.queryForObject("SELECT answer_key->>'expectedAnswer' FROM exam_questions WHERE source_question_id=?", String.class, q1);
        String ak2 = jdbc.queryForObject("SELECT answer_key->>'expectedAnswer' FROM exam_questions WHERE source_question_id=?", String.class, q2);
        assertThat(ak1).isEqualTo("2.50");
        assertThat(ak2).isEqualTo("02.5");
    }

    // -- Test group: pinned refresh (bank bump) --

    @Test
    void bankBumpPublishRefreshesFromPinnedVersion() {
        long bank = newBank();
        long q = srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null);
        long pinnedV = jdbc.queryForObject("SELECT id FROM question_versions WHERE question_id=? ORDER BY version_number DESC LIMIT 1", Long.class, q);
        Long examId = createExam("E6");
        composeDraft(examId, List.of(q)); // PUT pins v1
        // Bank bumps the question to version 2 (new current) + changes content.
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) "
                + "VALUES (" + q + ",2,'SINGLE_CHOICE','CHANGED','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        jdbc.update("UPDATE questions SET current_version_number = 2 WHERE id = ?", q);
        // Publish must refresh from PINNED v1 (not current v2): content stays the original.
        examService.publishExam(teacherUserId, examId, new PublishExamRequest(null));
        String content = jdbc.queryForObject("SELECT content FROM exam_questions WHERE source_question_id=?", String.class, q);
        assertThat(content).isEqualTo("c"); // pinned v1 content, NOT v2 "CHANGED"
        // sourceQuestionVersionId unchanged (still pinned v1).
        Long sv = jdbc.queryForObject("SELECT source_question_version_id FROM exam_questions WHERE source_question_id=?", Long.class, q);
        assertThat(sv).isEqualTo(pinnedV);
    }

    // -- Test group: publish-twice conflict --

    @Test
    void publishTwiceReturnsConflict() {
        long bank = newBank();
        Long examId = createExam("E7");
        composeDraft(examId, List.of(srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null)));
        examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)); // first -> PUBLISHED
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_PUBLISH_CONFLICT));
    }

    @Test
    void expectedVersionMismatchReturnsConflict() {
        long bank = newBank();
        Long examId = createExam("E8");
        composeDraft(examId, List.of(srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null)));
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(99)))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_PUBLISH_CONFLICT));
    }

    // -- Test group: rollback (invalid publish leaves state unchanged) --

    @Test
    void invalidPublishRollbackLeavesVersionDraft() {
        long bank = newBank();
        Long examId = createExam("E9");
        composeDraft(examId, List.of(srcQuestion(bank, "s3", "SINGLE_CHOICE", 3, 1, null))); // invalid (3 options)
        String examStatusBefore = jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId);
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
        // Version still DRAFT; exam status/cvn unchanged.
        assertThat(jdbc.queryForObject("SELECT status FROM exam_versions WHERE exam_id=?", String.class, examId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId)).isEqualTo(examStatusBefore);
    }

    // -- Test group: query count (no per-source SELECT growth) --

    @Test
    void publishSelectCountDoesNotGrowWithSourceCount() {
        Statistics stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        long bank1 = newBank();
        Long examN1 = createExam("Q1");
        composeDraft(examN1, List.of(srcQuestion(bank1, "s", "SINGLE_CHOICE", 4, 1, null)));
        stats.clear();
        examService.publishExam(teacherUserId, examN1, new PublishExamRequest(null));
        long n1 = selectExecutionCount(stats);

        long bank20 = newBank();
        List<Long> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(srcQuestion(bank20, "q" + i, "SINGLE_CHOICE", 4, 1, null));
        }
        Long examN20 = createExam("Q20");
        composeDraft(examN20, twenty);
        stats.clear();
        examService.publishExam(teacherUserId, examN20, new PublishExamRequest(null));
        long n20 = selectExecutionCount(stats);

        System.out.println("PUBLISH_QUERY_COUNT SELECT executions N=1=" + n1 + " N=20=" + n20);
        assertThat(Math.abs(n20 - n1))
                .as("SELECT execution delta N=1=%d N=20=%d must be constant (no per-source read)", n1, n20)
                .isLessThanOrEqualTo(4L);
        assertThat(n20).as("SELECT executions N=20").isLessThan(40L);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void assertPublishRejects(long bank, long qId) {
        Long examId = createExam("RX" + Math.abs(System.nanoTime() % 1_000_000_000L));
        composeDraft(examId, List.of(qId));
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    private void composeDraft(Long examId, List<Long> qIds) {
        List<CompositionQuestionRequest> qs = new ArrayList<>();
        for (int i = 0; i < qIds.size(); i++) {
            qs.add(new CompositionQuestionRequest(qIds.get(i), i, null));
        }
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(1, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, qs.isEmpty() ? List.of() : qs))));
    }

    private long newBank() {
        return insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES ("
                + schoolId + "," + subjectId + "," + teacherProfileId + ",'PB" + Math.abs(System.nanoTime() % 1_000_000_000L) + "','Bank')");
    }

    /** Builds an ACTIVE source question + current version + N options (first numCorrect correct). NUMERIC: 0 options + answerKey. */
    private long srcQuestion(long bank, String code, String type, int numOptions, int numCorrect, String answerKeyJson) {
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'" + code + "','ACTIVE',1," + teacherUserId + ")");
        String ak = answerKeyJson != null ? "'" + answerKeyJson + "'::jsonb" : "null";
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, answer_key, metadata, created_by) "
                + "VALUES (" + q + ",1,'" + type + "','c','MEDIUM',1," + ak + ",'{}'::jsonb," + teacherUserId + ")");
        for (int i = 0; i < numOptions; i++) {
            String key = String.valueOf((char) ('A' + i));
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + key + "','opt'," + (i < numCorrect) + "," + i + ")");
        }
        return q;
    }

    private String numericAk(String expectedAnswer) {
        return "{\"expectedAnswer\":\"" + expectedAnswer + "\",\"requiredInputLength\":4,\"roundingInstruction\":\"2dp\"}";
    }

    private Long createExam(String code) {
        return examService.createExam(teacherUserId,
                new com.hhtuann.backend.exam.dto.CreateExamRequest(subjectId, null, code, "T", null)).id();
    }

    private long selectExecutionCount(Statistics statistics) {
        return Arrays.stream(statistics.getQueries())
                .filter(Objects::nonNull)
                .filter(q -> q.trim().toLowerCase().startsWith("select"))
                .mapToLong(q -> statistics.getQueryStatistics(q).getExecutionCount())
                .sum();
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
