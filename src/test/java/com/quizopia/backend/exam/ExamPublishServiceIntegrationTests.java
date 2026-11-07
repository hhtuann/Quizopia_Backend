package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.dto.PublishedExamSummary;
import com.quizopia.backend.exam.dto.PublishExamRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level integration tests for POST /api/exams/{examId}/publish on the
 * one-exam-one-content model (Task B redesign, issue #2). Real PostgreSQL 17
 * Testcontainers. Covers the happy path (composition built via
 * updateDraftComposition with JPA @Version token 0), empty-composition and
 * empty-section rejection, per-type validation, archived bank/question
 * sources, snapshot refresh from pinned versions, numeric preservation,
 * publish-twice / wrong-token conflicts, rollback, and foreign-teacher 403.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamPublishServiceIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private ExamService examService;

    private long teacherUserId;
    private long subjectId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('pt','pt@t','h','PT')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'",
                teacherUserId);
        subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ("
                + "(SELECT id FROM grade_levels WHERE code='G10'), 'SUB','Sub')");
    }

    // -- Test group: happy path publish (4 types) --

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
        assertThat(r.publishedAt()).isNotNull();
        assertThat(r.questionCount()).isEqualTo(4);
        assertThat(r.totalPoints()).isEqualByComparingTo("4.00"); // 4 × default 1.00
        assertThat(r.durationMinutes()).isEqualTo(45);
        // Exam row is PUBLISHED with points + published_at set.
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT total_points FROM exams WHERE id=?",
                java.math.BigDecimal.class, examId)).isEqualByComparingTo("4.00");
        assertThat(jdbc.queryForObject("SELECT (published_at IS NOT NULL) FROM exams WHERE id=?",
                Boolean.class, examId)).isTrue();
        // Snapshot fields refreshed from the pinned versions.
        assertThat(jdbc.queryForObject(
                "SELECT difficulty FROM exam_questions WHERE exam_id=? AND source_question_id=?",
                String.class, examId, single)).isEqualTo("MEDIUM");
        assertThat(jdbc.queryForObject(
                "SELECT answer_key->>'expectedAnswer' FROM exam_questions WHERE exam_id=? AND source_question_id=?",
                String.class, examId, num)).isEqualTo("2.50");
        // Options materialized from the pinned versions per type.
        assertThat(optionCount(examId, single)).isEqualTo(4);
        assertThat(optionCount(examId, multi)).isEqualTo(4);
        assertThat(optionCount(examId, tf)).isEqualTo(4);
        assertThat(optionCount(examId, num)).isEqualTo(0);
    }

    // -- Test group: empty composition / empty section rejected --

    @Test
    void emptyCompositionRejected() {
        Long examId = createExam("E3");
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(0, null, null, List.of()));
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    @Test
    void emptySectionRejected() {
        Long examId = createExam("E4");
        // One section with zero questions.
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(0, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of()))));
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
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
        // NUMERIC with 1 option -> reject (bad shape; the V5 DB CHECK makes a
        // literally malformed expectedAnswer un-fixtureable, so the option-count
        // branch of the same validation is exercised).
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
        // The published exam_questions keep the answerKey verbatim (no
        // trim/normalize/float).
        String ak1 = jdbc.queryForObject(
                "SELECT answer_key->>'expectedAnswer' FROM exam_questions WHERE source_question_id=?", String.class,
                q1);
        String ak2 = jdbc.queryForObject(
                "SELECT answer_key->>'expectedAnswer' FROM exam_questions WHERE source_question_id=?", String.class,
                q2);
        assertThat(ak1).isEqualTo("2.50");
        assertThat(ak2).isEqualTo("02.5");
    }

    // -- Test group: pinned refresh (bank bump) --

    @Test
    void bankBumpPublishRefreshesFromPinnedVersion() {
        long bank = newBank();
        long q = srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null);
        long pinnedV = jdbc.queryForObject(
                "SELECT id FROM question_versions WHERE question_id=? ORDER BY version_number DESC LIMIT 1", Long.class,
                q);
        Long examId = createExam("E6");
        composeDraft(examId, List.of(q)); // PUT pins v1
        // Bank bumps the question to version 2 (new current) + changes content.
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) "
                + "VALUES (" + q + ",2,'SINGLE_CHOICE','CHANGED','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        jdbc.update("UPDATE questions SET current_version_number = 2 WHERE id = ?", q);
        entityManager.clear();
        // Publish must refresh from PINNED v1 (not current v2): content stays the
        // original.
        examService.publishExam(teacherUserId, examId, new PublishExamRequest(null));
        String content = jdbc.queryForObject("SELECT content FROM exam_questions WHERE source_question_id=?",
                String.class, q);
        assertThat(content).isEqualTo("c"); // pinned v1 content, NOT v2 "CHANGED"
        // sourceQuestionVersionId unchanged (still pinned v1).
        Long sv = jdbc.queryForObject(
                "SELECT source_question_version_id FROM exam_questions WHERE source_question_id=?", Long.class, q);
        assertThat(sv).isEqualTo(pinnedV);
    }

    // -- Test group: archived bank / question source rejected at publish --

    @Test
    void archivedBankSourceRejectedAtPublish() {
        long bank = newBank();
        long q = srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null);
        Long examId = createExam("Eab");
        composeDraft(examId, List.of(q));
        // Bank is archived AFTER the draft composition was built.
        jdbc.update("UPDATE question_banks SET status='ARCHIVED' WHERE id=?", bank);
        entityManager.clear();
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    @Test
    void archivedQuestionSourceRejectedAtPublish() {
        long bank = newBank();
        long q = srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null);
        Long examId = createExam("Eaq");
        composeDraft(examId, List.of(q));
        // Source question is archived AFTER the draft composition was built.
        jdbc.update("UPDATE questions SET status='ARCHIVED' WHERE id=?", q);
        entityManager.clear();
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    // -- Test group: publish-twice / wrong token conflicts --

    @Test
    void publishTwiceReturnsConflict() {
        long bank = newBank();
        Long examId = createExam("E7");
        composeDraft(examId, List.of(srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null)));
        examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)); // first -> PUBLISHED
        // Second publish (a "concurrent" repeat) -> 409 conflict.
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_PUBLISH_CONFLICT));
    }

    @Test
    void expectedVersionMismatchReturnsConflict() {
        long bank = newBank();
        Long examId = createExam("E8");
        composeDraft(examId, List.of(srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null)));
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(99)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_PUBLISH_CONFLICT));
    }

    @Test
    void expectedVersionMatchPublishSucceeds() {
        long bank = newBank();
        Long examId = createExam("E9");
        composeDraft(examId, List.of(srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null)));
        // After one composition PUT the exam's JPA @Version is 1; matching token publishes.
        PublishedExamSummary r = examService.publishExam(teacherUserId, examId, new PublishExamRequest(1));
        assertThat(r.status()).isEqualTo("PUBLISHED");
    }

    // -- Test group: rollback (invalid publish leaves state unchanged) --

    @Test
    void invalidPublishRollbackLeavesExamDraft() {
        long bank = newBank();
        Long examId = createExam("E10");
        composeDraft(examId, List.of(srcQuestion(bank, "s3", "SINGLE_CHOICE", 3, 1, null))); // invalid (3 options)
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
        // Exam row untouched: still DRAFT, total_points 0.
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT total_points FROM exams WHERE id=?",
                java.math.BigDecimal.class, examId)).isEqualByComparingTo("0");
    }

    // -- Test group: foreign teacher (mutation) -> 403 --

    @Test
    void foreignTeacherPublishReturnsAccessDenied() {
        long bank = newBank();
        Long examId = createExam("Efo");
        composeDraft(examId, List.of(srcQuestion(bank, "s", "SINGLE_CHOICE", 4, 1, null)));
        long other = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('o3','o3@t','h','O3')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", other);
        // Not the creator -> 403 EXAM_ACCESS_DENIED (creator-only management).
        assertThatThrownBy(() -> examService.publishExam(other, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_ACCESS_DENIED);
                            assertThat(ex.getErrorCode().statusCode()).isEqualTo(403);
                        });
    }

    // ============================================================
    // Helpers
    // ============================================================

    private int optionCount(long examId, long sourceQuestionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_question_options o JOIN exam_questions q ON o.exam_question_id = q.id "
                        + "WHERE q.exam_id=? AND q.source_question_id=?",
                Integer.class, examId, sourceQuestionId);
    }

    private void assertPublishRejects(long bank, long qId) {
        Long examId = createExam("RX" + Math.abs(System.nanoTime() % 1_000_000_000L));
        composeDraft(examId, List.of(qId));
        assertThatThrownBy(() -> examService.publishExam(teacherUserId, examId, new PublishExamRequest(null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    /** Builds a DRAFT composition on a FRESH exam (JPA @Version token 0). */
    private void composeDraft(Long examId, List<Long> qIds) {
        List<CompositionQuestionRequest> qs = new ArrayList<>();
        for (int i = 0; i < qIds.size(); i++) {
            qs.add(new CompositionQuestionRequest(qIds.get(i), i, null));
        }
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(0, 45, "Read carefully", List.of(
                        new CompositionSectionRequest(0, "S", null, qs))));
    }

    private long newBank() {
        return insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES ("
                + teacherUserId + "," + subjectId + ",'PB"
                + Math.abs(System.nanoTime() % 1_000_000_000L) + "','Bank')");
    }

    /**
     * Builds an ACTIVE source question + current version + N options (first
     * numCorrect correct). NUMERIC: 0 options + answerKey.
     */
    private long srcQuestion(long bank, String code, String type, int numOptions, int numCorrect,
            String answerKeyJson) {
        long q = insert(
                "INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES ("
                        + bank + ",'" + code + "','ACTIVE',1," + teacherUserId + ")");
        String ak = answerKeyJson != null ? "'" + answerKeyJson + "'::jsonb" : "null";
        long v = insert(
                "INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, answer_key, metadata, created_by) "
                        + "VALUES (" + q + ",1,'" + type + "','c','MEDIUM',1," + ak + ",'{}'::jsonb," + teacherUserId
                        + ")");
        for (int i = 0; i < numOptions; i++) {
            String key = String.valueOf((char) ('A' + i));
            jdbc.update(
                    "INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES ("
                            + v + ",'" + key + "','opt'," + (i < numCorrect) + "," + i + ")");
        }
        return q;
    }

    private String numericAk(String expectedAnswer) {
        return "{\"expectedAnswer\":\"" + expectedAnswer
                + "\",\"requiredInputLength\":4,\"roundingInstruction\":\"2dp\"}";
    }

    private Long createExam(String code) {
        return examService.createExam(teacherUserId,
                new com.quizopia.backend.exam.dto.CreateExamRequest(subjectId, null, code, "T", null)).id();
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
