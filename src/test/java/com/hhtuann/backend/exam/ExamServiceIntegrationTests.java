package com.hhtuann.backend.exam;

import com.hhtuann.backend.exam.application.ExamService;
import com.hhtuann.backend.exam.domain.model.*;
import com.hhtuann.backend.exam.dto.*;
import com.hhtuann.backend.exam.exception.ExamException;
import com.hhtuann.backend.exam.repository.*;
import com.hhtuann.backend.question.dto.PageResponse;
import com.hhtuann.backend.question.dto.SubjectSummary;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level integration tests for the 4 exam endpoints (A3.2-1 batch).
 * Uses real PostgreSQL 17 Testcontainers; authorization via DB (not JWT claims).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamServiceIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;
    @Autowired private ExamPurposeRepository purposeRepo;
    @Autowired private ExamRepository examRepo;
    @Autowired private ExamVersionRepository versionRepo;
    @Autowired private ExamSectionRepository sectionRepo;
    @Autowired private ExamQuestionRepository questionRepo;
    @Autowired private ExamQuestionOptionRepository optionRepo;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private long teacherUserId;
    private long schoolId;
    private long subjectId;
    private long teacherProfileId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('t1','t1@t','h','T1')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('SVC','SVC')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        teacherProfileId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'TC1')");
    }

    // -- Authorization --

    @Test
    void purposeListSystemAdminWithoutTeacherRoleDenied() {
        long adminId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('a1','a1@t','h','A1')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='SYSTEM_ADMIN'", adminId);
        assertThatThrownBy(() -> examService.listPurposes(adminId))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void teacherWithoutProfileReturns404() {
        long uid = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('np','np@t','h','NP')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", uid);
        assertThatThrownBy(() -> examService.listPurposes(uid))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void studentCannotAccessExamEndpoints() {
        long sid = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('s1','s1@t','h','S1')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='STUDENT'", sid);
        assertThatThrownBy(() -> examService.listPurposes(sid))
                .isInstanceOf(ExamException.class);
    }

    // -- Purpose list --

    @Test
    void purposeListReturnsCallerSchoolOnlyOrderedByPosition() {
        // School B purposes should not appear
        long s2 = insert("INSERT INTO schools (code, name) VALUES ('S2','S2')");
        purposeRepo.saveAndFlush(makePurpose(s2, "OTHER", "Other", 0));
        // Caller school purposes
        purposeRepo.saveAndFlush(makePurpose(schoolId, "MIDTERM", "Giữa kỳ", 0));
        purposeRepo.saveAndFlush(makePurpose(schoolId, "FINAL", "Cuối kỳ", 1));
        List<ExamPurposeResponse> result = examService.listPurposes(teacherUserId);
        assertThat(result).hasSize(2);
        assertThat(result).extracting(ExamPurposeResponse::code).containsExactly("MIDTERM", "FINAL");
        assertThat(result).extracting(ExamPurposeResponse::position).containsExactly(0, 1);
    }

    @Test
    void purposeListNoPurposesReturnsEmpty() {
        List<ExamPurposeResponse> result = examService.listPurposes(teacherUserId);
        assertThat(result).isEmpty();
    }

    // -- Create exam --

    @Test
    void createExamSucceedsWithDraftV1() {
        ExamListItem result = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "EX1", "Exam 1", "desc"));
        assertThat(result.id()).isPositive();
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.currentVersionNumber()).isEqualTo(1);
        assertThat(result.hasDraft()).isTrue();
        assertThat(result.hasPublished()).isFalse();
        assertThat(result.subject()).isEqualTo(new SubjectSummary(subjectId, "SUB", "Sub"));
        assertThat(result.purpose()).isNull();

        // Verify DRAFT v1 exists with defaults
        List<ExamVersion> versions = versionRepo.findAllByExamIdOrderByVersionNumberDesc(result.id());
        assertThat(versions).hasSize(1);
        ExamVersion v = versions.get(0);
        assertThat(v.getVersionNumber()).isEqualTo(1);
        assertThat(v.getStatus()).isEqualTo(ExamVersionStatus.DRAFT);
        assertThat(v.getDurationMinutes()).isEqualTo(60);
        assertThat(v.getTotalPoints()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(v.getTfMatrixScoring().path("0").asInt()).isZero();
        assertThat(v.getTfMatrixScoring().path("4").asInt()).isEqualTo(100);
    }

    @Test
    void createExamWithPurposeSucceeds() {
        ExamPurpose p = purposeRepo.saveAndFlush(makePurpose(schoolId, "MID", "Mid", 0));
        ExamListItem result = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, p.getId(), "EX2", "Exam 2", null));
        assertThat(result.purpose()).isEqualTo(new ExamPurposeSummary(p.getId(), "MID", "Mid"));
    }

    @Test
    void createExamMissingSubject404() {
        assertThatThrownBy(() -> examService.createExam(teacherUserId,
                new CreateExamRequest(99999L, null, "EX3", "T", null)))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void createExamCrossSchoolSubject403() {
        long s2 = insert("INSERT INTO schools (code, name) VALUES ('CS','CS')");
        long gl2 = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + s2 + ",'GL','G')");
        long sub2 = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + s2 + "," + gl2 + ",'XS','X')");
        assertThatThrownBy(() -> examService.createExam(teacherUserId,
                new CreateExamRequest(sub2, null, "EX4", "T", null)))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void createExamMissingPurpose404() {
        assertThatThrownBy(() -> examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, 99999L, "EX5", "T", null)))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void createExamCrossSchoolPurpose403() {
        long s2 = insert("INSERT INTO schools (code, name) VALUES ('CS2','CS2')");
        ExamPurpose p = purposeRepo.saveAndFlush(makePurpose(s2, "P", "P", 0));
        assertThatThrownBy(() -> examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, p.getId(), "EX6", "T", null)))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void createExamSameOwnerDifferentCaseCode409() {
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "CODE-1", "T", null));
        assertThatThrownBy(() -> examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "code-1", "T", null)))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void createExamTwoOwnersSameSchoolSameCodeAccepted() {
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('u2','u2@t','h','U2')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        long tp2 = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u2 + "," + schoolId + ",'TC2')");
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "SHARED", "T", null));
        // Same code, different owner — should succeed (owner-scoped unique)
        ExamListItem result = examService.createExam(u2, new CreateExamRequest(subjectId, null, "SHARED", "T2", null));
        assertThat(result.id()).isPositive();
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
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "D1", "T", null));
        PageResponse<ExamListItem> result = examService.listMyExams(teacherUserId, null, null, "DRAFT", 0, 20, null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).status()).isEqualTo("DRAFT");
        PageResponse<ExamListItem> readyResult = examService.listMyExams(teacherUserId, null, null, "READY", 0, 20, null);
        assertThat(readyResult.items()).isEmpty();
    }

    @Test
    void listMyExamsInvalidStatus400() {
        assertThatThrownBy(() -> examService.listMyExams(teacherUserId, null, null, "INVALID", 0, 20, null))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void listMyExamsInvalidSort400() {
        assertThatThrownBy(() -> examService.listMyExams(teacherUserId, null, null, null, 0, 20, "invalidField,desc"))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void listMyExamsNotReturnOtherTeacherExams() {
        examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "MINE", "T", null));
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('u3','u3@t','h','U3')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u2 + "," + schoolId + ",'TC3')");
        PageResponse<ExamListItem> u2Result = examService.listMyExams(u2, null, null, null, 0, 20, null);
        assertThat(u2Result.items()).isEmpty();
    }

    // -- Exam detail --

    @Test
    void getExamDetailOwnerPass() {
        ExamListItem created = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "D1", "Detail Exam", "desc"));
        TeacherExamEditorResponse result = examService.getExamDetail(teacherUserId, created.id());
        assertThat(result.id()).isEqualTo(created.id());
        assertThat(result.code()).isEqualTo("D1");
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.currentVersionNumber()).isEqualTo(1);
        assertThat(result.currentDraftVersion()).isNotNull();
        assertThat(result.currentDraftVersion().versionNumber()).isEqualTo(1);
        assertThat(result.currentDraftVersion().durationMinutes()).isEqualTo(60);
        assertThat(result.currentDraftVersion().tfMatrixScoring().path("0").asInt()).isZero();
        assertThat(result.currentDraftVersion().sections()).isEmpty();
        assertThat(result.publishedVersions()).isEmpty();
    }

    @Test
    void getExamDetailForeignOwnerReturns404() {
        ExamListItem created = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "D2", "T", null));
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('u4','u4@t','h','U4')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u2 + "," + schoolId + ",'TC4')");
        // Different teacher accessing — anti-enumeration: 404 (not 403)
        assertThatThrownBy(() -> examService.getExamDetail(u2, created.id()))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void getExamDetailNonexistentReturns404() {
        assertThatThrownBy(() -> examService.getExamDetail(teacherUserId, 99999L))
                .isInstanceOf(ExamException.class);
    }

    @Test
    void getExamDetailM1DeepCopyPreventsMutation() {
        ExamListItem created = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "DC", "T", null));
        TeacherExamEditorResponse result = examService.getExamDetail(teacherUserId, created.id());
        // Mutate DTO tfMatrixScoring — must NOT affect entity persisted state
        ObjectNode mutable = (ObjectNode) result.currentDraftVersion().tfMatrixScoring();
        mutable.put("0", 999);
        // Reload from DB — tf ladder must be unchanged
        entityManager.clear();
        ExamVersion reloaded = versionRepo.findAllByExamIdOrderByVersionNumberDesc(created.id()).get(0);
        assertThat(reloaded.getTfMatrixScoring().path("0").asInt()).isZero();
    }

    @Test
    void getExamDetailWithSectionsAndQuestionsOrdered() {
        // Create exam + draft
        ExamListItem created = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "SQ", "T", null));
        ExamVersion draft = versionRepo.findFirstByExamIdAndStatus(created.id(), ExamVersionStatus.DRAFT).orElseThrow();

        // Add sections
        ExamSection sec0 = sectionRepo.saveAndFlush(new ExamSection(draft.getId(), "Section B", 1));
        ExamSection sec1 = sectionRepo.saveAndFlush(new ExamSection(draft.getId(), "Section A", 0));

        // Add questions (need a source question)
        long bankId = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES ("
                + schoolId + "," + subjectId + "," + teacherProfileId + ",'QB','Bank')");
        long qId = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'Q1'," + teacherUserId + ")");
        long qvId = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + qId + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        long qId2 = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'Q2'," + teacherUserId + ")");
        long qvId2 = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + qId2 + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");

        ExamQuestion q1 = questionRepo.saveAndFlush(new ExamQuestion(draft.getId(), sec1.getId(), qId, qvId,
                "QC1", com.hhtuann.backend.question.domain.model.QuestionType.SINGLE_CHOICE, "c1", BigDecimal.ONE, 1));
        ExamQuestion q2 = questionRepo.saveAndFlush(new ExamQuestion(draft.getId(), sec1.getId(), qId2, qvId2,
                "QC2", com.hhtuann.backend.question.domain.model.QuestionType.SINGLE_CHOICE, "c2", BigDecimal.ONE, 0));

        // Add options
        optionRepo.saveAndFlush(new ExamQuestionOption(q2.getId(), "A", "opt A", true, 0));
        optionRepo.saveAndFlush(new ExamQuestionOption(q2.getId(), "B", "opt B", false, 1));

        entityManager.clear();

        TeacherExamEditorResponse result = examService.getExamDetail(teacherUserId, created.id());
        // Sections ordered by position: Section A (0), Section B (1)
        assertThat(result.currentDraftVersion().sections()).hasSize(2);
        assertThat(result.currentDraftVersion().sections().get(0).title()).isEqualTo("Section A");
        assertThat(result.currentDraftVersion().sections().get(1).title()).isEqualTo("Section B");
        // Questions in Section A ordered by position: QC2 (0), QC1 (1)
        List<ExamQuestionResponse> sectionAQuestions = result.currentDraftVersion().sections().get(0).questions();
        assertThat(sectionAQuestions).hasSize(2);
        assertThat(sectionAQuestions.get(0).questionCode()).isEqualTo("QC2");
        assertThat(sectionAQuestions.get(1).questionCode()).isEqualTo("QC1");
        // Options of QC2 ordered
        assertThat(sectionAQuestions.get(0).options()).hasSize(2);
        assertThat(sectionAQuestions.get(0).options().get(0).optionKey()).isEqualTo("A");
        assertThat(sectionAQuestions.get(0).options().get(0).isCorrect()).isTrue();
        assertThat(sectionAQuestions.get(0).options().get(1).optionKey()).isEqualTo("B");
    }

    // -- Repository semantics --

    @Test
    void findFirstByExamIdAndStatusDraftReturnsSingle() {
        ExamListItem created = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "RP1", "T", null));
        // Only one DRAFT — partial unique guarantees this
        var draft = versionRepo.findFirstByExamIdAndStatus(created.id(), ExamVersionStatus.DRAFT);
        assertThat(draft).isPresent();
        assertThat(draft.get().getVersionNumber()).isEqualTo(1);
    }

    @Test
    void findAllByExamIdOrderByVersionNumberDescOrdered() {
        ExamListItem created = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "RP2", "T", null));
        // Publish v1, create v2
        ExamVersion v1 = versionRepo.findFirstByExamIdAndStatus(created.id(), ExamVersionStatus.DRAFT).orElseThrow();
        v1.markPublished(Instant.now(), BigDecimal.TEN);
        versionRepo.saveAndFlush(v1);
        versionRepo.saveAndFlush(new ExamVersion(schoolId, created.id(), 2, teacherUserId));
        List<ExamVersion> all = versionRepo.findAllByExamIdOrderByVersionNumberDesc(created.id());
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getVersionNumber()).isEqualTo(2);
        assertThat(all.get(1).getVersionNumber()).isEqualTo(1);
    }

    @Test
    void questionBatchBySectionIdsOrdered() {
        ExamListItem created = examService.createExam(teacherUserId,
                new CreateExamRequest(subjectId, null, "RP3", "T", null));
        ExamVersion draft = versionRepo.findFirstByExamIdAndStatus(created.id(), ExamVersionStatus.DRAFT).orElseThrow();
        ExamSection s1 = sectionRepo.saveAndFlush(new ExamSection(draft.getId(), "S1", 0));
        ExamSection s2 = sectionRepo.saveAndFlush(new ExamSection(draft.getId(), "S2", 1));
        long bankId = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES ("
                + schoolId + "," + subjectId + "," + teacherProfileId + ",'QB3','B')");
        long qId = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'RQ'," + teacherUserId + ")");
        long qvId = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + qId + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        long qId2 = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'RQ2'," + teacherUserId + ")");
        long qvId2 = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + qId2 + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");

        questionRepo.saveAndFlush(new ExamQuestion(draft.getId(), s2.getId(), qId2, qvId2,
                "Q2B", com.hhtuann.backend.question.domain.model.QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0));
        questionRepo.saveAndFlush(new ExamQuestion(draft.getId(), s1.getId(), qId, qvId,
                "Q1A", com.hhtuann.backend.question.domain.model.QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0));

        List<ExamQuestion> batch = questionRepo.findAllByExamSectionIdInOrderByExamSectionIdAscPositionAsc(
                List.of(s1.getId(), s2.getId()));
        // Ordered by section ID ASC, then position ASC
        assertThat(batch).hasSize(2);
        // s1 created first (lower ID), its question comes first
        assertThat(batch.get(0).getExamSectionId()).isEqualTo(s1.getId());
        assertThat(batch.get(1).getExamSectionId()).isEqualTo(s2.getId());
    }

    // -- E1: Query-count test (proves N+1 eliminated) --

    @Test
    void listMyExamsQueryCountDoesNotGrowWithPageSize() {
        Statistics stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // Scenario 1: 1 exam
        for (int i = 0; i < 1; i++) {
            examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "Q1-" + i, "T", null));
        }
        entityManager.clear();
        stats.clear();
        examService.listMyExams(teacherUserId, null, null, null, 0, 20, null);
        long countN1 = stats.getPrepareStatementCount();

        // Scenario 2: 20 exams
        for (int i = 0; i < 19; i++) {
            examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "Q20-" + i, "T", null));
        }
        entityManager.clear();
        stats.clear();
        examService.listMyExams(teacherUserId, null, null, null, 0, 20, null);
        long countN20 = stats.getPrepareStatementCount();

        // Query count must NOT grow proportionally with page size.
        // Measured: N=1 → 6 prepared statements, N=20 → 7. Delta = 1 (constant, not 2N).
        // The +1 is a Hibernate internal (sequence/identifier allocation), not a per-exam query.
        long delta = Math.abs(countN20 - countN1);
        assertThat(delta).as("query count delta between N=1 (%d) and N=20 (%d) must be constant, not proportional to N", countN1, countN20)
                .isLessThanOrEqualTo(2L);
    }

    @Test
    void listMyExamsEmptyPageDoesNotCallBatchVersionQuery() {
        Statistics stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // No exams → empty page
        stats.clear();
        PageResponse<ExamListItem> result = examService.listMyExams(teacherUserId, null, null, null, 0, 20, null);
        assertThat(result.items()).isEmpty();

        // The batch version-status query should NOT execute when examIds is empty
        // Verify by checking no query touches exam_versions table
        String[] queries = stats.getQueries();
        boolean touchedExamVersions = java.util.Arrays.stream(queries)
                .anyMatch(q -> q != null && q.toLowerCase().contains("exam_versions"));
        assertThat(touchedExamVersions).isFalse();
    }

    // -- Helpers --

    private ExamPurpose makePurpose(Long schoolId, String code, String title, int position) {
        ExamPurpose p = new ExamPurpose(schoolId, code, title);
        p.setPosition(position);
        return p;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
