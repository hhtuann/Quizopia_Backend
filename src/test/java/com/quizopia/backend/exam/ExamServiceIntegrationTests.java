package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.domain.model.ExamQuestion;
import com.quizopia.backend.exam.domain.model.ExamQuestionOption;
import com.quizopia.backend.exam.domain.model.ExamSection;
import com.quizopia.backend.exam.dto.CreateExamRequest;
import com.quizopia.backend.exam.dto.ExamListItem;
import com.quizopia.backend.exam.dto.TeacherExamEditorResponse;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.exam.repository.ExamQuestionOptionRepository;
import com.quizopia.backend.exam.repository.ExamQuestionRepository;
import com.quizopia.backend.exam.repository.ExamSectionRepository;
import com.quizopia.backend.question.domain.model.QuestionType;
import com.quizopia.backend.question.dto.PageResponse;
import com.quizopia.backend.question.dto.SubjectSummary;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level integration tests for the one-exam-one-content Exam CRUD
 * surface (Task B redesign, issue #2): create (user-owned, with or without
 * subject metadata), creator-scoped listing, management detail, duplicate,
 * and archive with its session guard. Uses real PostgreSQL 17 Testcontainers;
 * authorization via DB (not JWT claims).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
@SuppressWarnings({ "null" })
class ExamServiceIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private ExamService examService;
    @Autowired
    private ExamSectionRepository sectionRepo;
    @Autowired
    private ExamQuestionRepository questionRepo;
    @Autowired
    private ExamQuestionOptionRepository optionRepo;

    private long teacherUserId;
    private long subjectId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('t1','t1@t','h','T1')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'",
                teacherUserId);
        subjectId = insert(
                "INSERT INTO subjects (grade_level_id, code, name) VALUES ((SELECT id FROM grade_levels WHERE code='G10'), 'SUB','Sub')");
    }

    // -- Create exam --

    @Test
    void createExamSucceedsWithDraftDefaults() {
        ExamListItem result = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "EX1", "Exam 1", "desc"));
        assertThat(result.id()).isPositive();
        assertThat(result.code()).isEqualTo("EX1");
        assertThat(result.title()).isEqualTo("Exam 1");
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.questionCount()).isZero();
        assertThat(result.totalPoints()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.publishedAt()).isNull();
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.purpose()).isNull();
        assertThat(result.subject()).isEqualTo(new SubjectSummary(subjectId, "SUB", "Sub"));
        // The exam is owned by its creator.
        assertThat(jdbc.queryForObject(
                "SELECT created_by_user_id FROM exams WHERE id = ?",
                Long.class, result.id())).isEqualTo(teacherUserId);
    }

    @Test
    void createExamDuplicateCodeSameCreatorConflict() {
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "CODE-1", "T", null));
        assertThatThrownBy(() -> examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "code-1", "T", null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_CODE_CONFLICT));
    }

    // -- Create exam (no subject metadata) --

    @Test
    void createExamSucceedsWithNullSubject() {
        // Subject is optional metadata: any TEACHER user may create an
        // exam without one.
        long soloUserId = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('solo','solo@t','h','SOLO')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'",
                soloUserId);
        ExamListItem result = examService.createExam(soloUserId,
                new CreateExamRequest(null, null, "PEX", "Personal", null));
        assertThat(result.id()).isPositive();
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.subject()).isNull();
        assertThat(result.questionCount()).isZero();
        assertThat(result.totalPoints()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject(
                "SELECT created_by_user_id FROM exams WHERE id = ?",
                Long.class, result.id())).isEqualTo(soloUserId);
    }

    // -- List my exams --

    @Test
    void listMyExamsOwnerScoped() {
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "A", "TA", null));
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "B", "TB", null));
        PageResponse<ExamListItem> result = examService.listMyExams(teacherUserId, null, null, null, 0, 20, null);
        assertThat(result.items()).hasSize(2);
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    void listMyExamsSearchCodeCaseInsensitive() {
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "ALPHA", "TA", null));
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "BETA", "TB", null));
        PageResponse<ExamListItem> result = examService.listMyExams(teacherUserId, "alpha", null, null, 0, 20, null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).code()).isEqualTo("ALPHA");
    }

    @Test
    void listMyExamsSearchTitleCaseInsensitive() {
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "X", "Special Title", null));
        PageResponse<ExamListItem> result = examService.listMyExams(teacherUserId, "special", null, null, 0, 20, null);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void listMyExamsStatusFilter() {
        Long draftId = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "D1", "T", null)).id();
        Long publishedId = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "P1", "T", null)).id();
        publishViaSql(publishedId);
        entityManager.clear();
        PageResponse<ExamListItem> drafts = examService.listMyExams(teacherUserId, null, null, "DRAFT", 0, 20, null);
        assertThat(drafts.items()).hasSize(1);
        assertThat(drafts.items().get(0).id()).isEqualTo(draftId);
        PageResponse<ExamListItem> published = examService.listMyExams(teacherUserId, null, null, "PUBLISHED", 0, 20,
                null);
        assertThat(published.items()).hasSize(1);
        assertThat(published.items().get(0).id()).isEqualTo(publishedId);
        assertThat(published.items().get(0).publishedAt()).isNotNull();
    }

    @Test
    void listMyExamsInvalidStatusRejected() {
        assertThatThrownBy(() -> examService.listMyExams(teacherUserId, null, null, "INVALID", 0, 20, null))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    @Test
    void listMyExamsInvalidSortRejected() {
        assertThatThrownBy(() -> examService.listMyExams(teacherUserId, null, null, null, 0, 20, "invalidField,desc"))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    @Test
    void listMyExamsPagination() {
        for (int i = 0; i < 3; i++) {
            examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "PG" + i, "T", null));
        }
        PageResponse<ExamListItem> page0 = examService.listMyExams(teacherUserId, null, null, null, 0, 2, null);
        assertThat(page0.items()).hasSize(2);
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        PageResponse<ExamListItem> page1 = examService.listMyExams(teacherUserId, null, null, null, 1, 2, null);
        assertThat(page1.items()).hasSize(1);
    }

    @Test
    void listMyExamsNotReturnOtherTeacherExams() {
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "MINE", "T", null));
        long u2 = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('u3','u3@t','h','U3')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        PageResponse<ExamListItem> u2Result = examService.listMyExams(u2, null, null, null, 0, 20, null);
        assertThat(u2Result.items()).isEmpty();
    }

    // -- Exam detail --

    @Test
    void getExamDetailOwnShowsComposition() {
        Long examId = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "D1", "Detail Exam", "desc")).id();
        long[] src = sourceQuestionFixture();
        ExamSection section = sectionRepo.saveAndFlush(new ExamSection(examId, "Section A", 0));
        ExamQuestion q1 = questionRepo.saveAndFlush(new ExamQuestion(examId, section.getId(), src[0], src[1],
                "QC1", QuestionType.SINGLE_CHOICE, "c1", BigDecimal.ONE, 0));
        optionRepo.saveAndFlush(new ExamQuestionOption(q1.getId(), "A", "opt A", true, 0));
        optionRepo.saveAndFlush(new ExamQuestionOption(q1.getId(), "B", "opt B", false, 1));
        entityManager.clear();

        TeacherExamEditorResponse result = examService.getExamDetail(teacherUserId, examId);
        assertThat(result.id()).isEqualTo(examId);
        assertThat(result.code()).isEqualTo("D1");
        assertThat(result.description()).isEqualTo("desc");
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.subject()).isEqualTo(new SubjectSummary(subjectId, "SUB", "Sub"));
        assertThat(result.examVersion()).isZero();
        assertThat(result.sourceExamId()).isNull();
        assertThat(result.publishedAt()).isNull();
        assertThat(result.tfMatrixScoring().path("4").asInt()).isEqualTo(100);
        assertThat(result.composition()).hasSize(1);
        assertThat(result.composition().get(0).title()).isEqualTo("Section A");
        var qs = result.composition().get(0).questions();
        assertThat(qs).hasSize(1);
        assertThat(qs.get(0).questionCode()).isEqualTo("QC1");
        assertThat(qs.get(0).questionType()).isEqualTo("SINGLE_CHOICE");
        assertThat(qs.get(0).sourceQuestionId()).isEqualTo(src[0]);
        assertThat(qs.get(0).sourceQuestionVersionId()).isEqualTo(src[1]);
        assertThat(qs.get(0).defaultPoints()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(qs.get(0).options()).hasSize(2);
        assertThat(qs.get(0).options().get(0).optionKey()).isEqualTo("A");
        assertThat(qs.get(0).options().get(0).isCorrect()).isTrue();
        assertThat(qs.get(0).options().get(1).optionKey()).isEqualTo("B");
        assertThat(qs.get(0).sectionId()).isEqualTo(section.getId());
    }

    @Test
    void getExamDetailForeignTeacherReturns404() {
        Long examId = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "D2", "T", null)).id();
        long u2 = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('u4','u4@t','h','U4')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        // Different user (non-creator) — anti-enumeration: 404.
        assertThatThrownBy(() -> examService.getExamDetail(u2, examId))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_NOT_FOUND);
                            assertThat(ex.getErrorCode().statusCode()).isEqualTo(404);
                        });
    }

    @Test
    void getExamDetailNonexistentReturns404() {
        assertThatThrownBy(() -> examService.getExamDetail(teacherUserId, 99999L))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_NOT_FOUND));
    }

    @Test
    void getExamDetailShowsPublishedState() {
        Long examId = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "DP", "T", null)).id();
        publishViaSql(examId);
        entityManager.clear();
        TeacherExamEditorResponse result = examService.getExamDetail(teacherUserId, examId);
        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(result.publishedAt()).isNotNull();
        assertThat(result.totalPoints()).isEqualByComparingTo(BigDecimal.ONE);
    }

    // -- Duplicate --

    @Test
    void duplicateExamDeepCopiesCompositionAndSetsSourceProvenance() {
        Long originalId = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "DUP", "Original", "d")).id();
        long[] src = sourceQuestionFixture();
        ExamSection section = sectionRepo.saveAndFlush(new ExamSection(originalId, "S", 0));
        ExamQuestion q = questionRepo.saveAndFlush(new ExamQuestion(originalId, section.getId(), src[0], src[1],
                "QC1", QuestionType.SINGLE_CHOICE, "c1", new BigDecimal("2.50"), 0));
        optionRepo.saveAndFlush(new ExamQuestionOption(q.getId(), "A", "opt A", true, 0));

        ExamListItem copy = examService.duplicateExam(teacherUserId, originalId);

        assertThat(copy.id()).isPositive().isNotEqualTo(originalId);
        assertThat(copy.status()).isEqualTo("DRAFT");
        assertThat(copy.title()).isEqualTo("Original");
        // Note: the duplicate response is a creation-style list item (questionCount
        // 0); the copied question count is asserted below via the copy's detail.
        assertThat(copy.totalPoints()).isEqualByComparingTo(BigDecimal.ZERO); // DRAFT again
        // Copy carries duplicate provenance + deep-copied composition.
        TeacherExamEditorResponse copyDetail = examService.getExamDetail(teacherUserId, copy.id());
        assertThat(copyDetail.sourceExamId()).isEqualTo(originalId);
        assertThat(copyDetail.composition()).hasSize(1);
        assertThat(copyDetail.composition().get(0).questions()).hasSize(1);
        assertThat(copyDetail.composition().get(0).questions().get(0).questionCode()).isEqualTo("QC1");
        assertThat(copyDetail.composition().get(0).questions().get(0).defaultPoints())
                .isEqualByComparingTo("2.50");
        assertThat(copyDetail.composition().get(0).questions().get(0).options()).hasSize(1);
        // Original untouched: no duplicate provenance, composition intact.
        TeacherExamEditorResponse originalDetail = examService.getExamDetail(teacherUserId, originalId);
        assertThat(originalDetail.sourceExamId()).isNull();
        assertThat(originalDetail.composition()).hasSize(1);
        assertThat(originalDetail.composition().get(0).questions()).hasSize(1);
    }

    // -- Archive --

    @Test
    void archivePublishedExamWithoutSessionsSucceeds() {
        Long examId = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "AR", "T", null)).id();
        publishViaSql(examId);
        entityManager.clear();
        TeacherExamEditorResponse result = examService.archiveExam(teacherUserId, examId);
        assertThat(result.status()).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
                .isEqualTo("ARCHIVED");
    }

    @Test
    void archiveExamWithOpenSessionConflict() {
        Long examId = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "ARO", "T", null)).id();
        publishViaSql(examId);
        insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, "
                + "max_attempts, duration_minutes, created_by, opened_at) VALUES (" + examId
                + ",'OS1','T','OPEN', now() - interval '1 hour', now() + interval '1 hour', 1, 60, "
                + teacherUserId + ", now())");
        entityManager.clear();
        assertThatThrownBy(() -> examService.archiveExam(teacherUserId, examId))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_ARCHIVE_CONFLICT));
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void archiveExamIdempotentWhenAlreadyArchived() {
        Long examId = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "ARI", "T", null)).id();
        publishViaSql(examId);
        entityManager.clear();
        examService.archiveExam(teacherUserId, examId);
        // Second archive is a no-op returning the ARCHIVED state.
        TeacherExamEditorResponse again = examService.archiveExam(teacherUserId, examId);
        assertThat(again.status()).isEqualTo("ARCHIVED");
    }

    // -- Helpers --

    /** Publishes the exam directly via SQL (status + published invariant columns). */
    private void publishViaSql(Long examId) {
        jdbc.update("UPDATE exams SET status = 'PUBLISHED', published_at = now(), total_points = 1 WHERE id = ?",
                examId);
    }

    /**
     * Creates an ACTIVE SINGLE_CHOICE source question + current version (raw
 * SQL, creator-owned bank). Returns {questionId, versionId}.
     */
    private long[] sourceQuestionFixture() {
        long bankId = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES ("
                + teacherUserId + "," + subjectId + ",'QB','Bank')");
        long qId = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES ("
                + bankId + ",'Q1','ACTIVE',1," + teacherUserId + ")");
        long qvId = insert(
                "INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES ("
                        + qId + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        return new long[] { qId, qvId };
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
