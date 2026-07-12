package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.domain.model.ExamVersionStatus;
import com.quizopia.backend.exam.dto.TeacherExamEditorResponse;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.exam.repository.ExamQuestionRepository;
import com.quizopia.backend.exam.repository.ExamSectionRepository;
import com.quizopia.backend.exam.repository.ExamVersionRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
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
 * Service-level integration tests for PUT /api/exams/{examId}/draft/composition
 * (A3.2-2A). Real PostgreSQL 17 Testcontainers; authorization via DB. Covers
 * snapshot pinning, atomic replace + rollback, strict source ownership, numeric
 * preservation, expected-version optimistic token, and no per-source query growth.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamCompositionServiceIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;
    @Autowired private ExamVersionRepository versionRepo;
    @Autowired private ExamSectionRepository sectionRepo;
    @Autowired private ExamQuestionRepository questionRepo;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private long teacherUserId;
    private long schoolId;
    private long subjectId;
    private long teacherProfileId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('tc','tc@t','h','TC')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('SC','School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        teacherProfileId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'TC1')");
    }

    // -- Test group: COMPOSITION SUCCESS (4 types) --

    @Test
    void compositionSuccessAllFourTypesSnapshotExact() {
        Bank b = bank4("B1");
        Long examId = createExam("E1");

        TeacherExamEditorResponse r = examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, 75, "Read carefully", List.of(
                        new CompositionSectionRequest(0, "Section A", "sec instructions", List.of(
                                new CompositionQuestionRequest(b.singleQ, 0, null),
                                new CompositionQuestionRequest(b.multiQ, 1, new java.math.BigDecimal("2.50")),
                                new CompositionQuestionRequest(b.tfQ, 2, null),
                                new CompositionQuestionRequest(b.numQ, 3, null))))));

        // Draft settings updated.
        assertThat(r.currentDraftVersion().durationMinutes()).isEqualTo(75);
        assertThat(r.currentDraftVersion().instructions()).isEqualTo("Read carefully");
        // One section, four questions.
        assertThat(r.currentDraftVersion().sections()).hasSize(1);
        var qs = r.currentDraftVersion().sections().get(0).questions();
        assertThat(qs).hasSize(4);
        assertThat(qs).extracting(q -> q.questionType())
                .containsExactly("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE_MATRIX", "NUMERIC_FILL");
        // Pinned source version ids match the source question current versions.
        assertThat(qs).extracting(q -> q.sourceQuestionVersionId())
                .containsExactly(b.singleV, b.multiV, b.tfV, b.numV);
        // Snapshot content copied from the pinned versions.
        assertThat(qs.get(0).content()).isEqualTo("single");
        assertThat(qs.get(0).options()).hasSize(4);
        assertThat(qs.get(0).options().get(0).isCorrect()).isTrue();
        assertThat(qs.get(1).defaultPoints()).isEqualByComparingTo("2.50"); // teacher override
        assertThat(qs.get(2).options()).hasSize(4); // TF exactly 4
        assertThat(qs.get(3).options()).isEmpty(); // NUMERIC has 0 options
        assertThat(qs.get(3).answerKey().path("expectedAnswer").asString()).isEqualTo("2.50");
    }

    // -- Test group: defaultPoints override + fallback --

    @Test
    void defaultPointsOverrideAndFallback() {
        Bank b = bank4("B2");
        Long examId = createExam("E2");
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(
                                new CompositionQuestionRequest(b.singleQ, 0, new java.math.BigDecimal("5.00")),
                                new CompositionQuestionRequest(b.multiQ, 1, null))))));
        var qs = questionRepo.findAllByExamVersionId(draftId(examId));
        var single = qs.stream().filter(q -> q.getSourceQuestionId() == b.singleQ).findFirst().orElseThrow();
        var multi = qs.stream().filter(q -> q.getSourceQuestionId() == b.multiQ).findFirst().orElseThrow();
        assertThat(single.getDefaultPoints()).isEqualByComparingTo("5.00"); // override
        assertThat(multi.getDefaultPoints()).isEqualByComparingTo("1.00"); // version default
    }

    // -- Test group: REPLACE clears old graph --

    @Test
    void replaceClearsOldGraph() {
        Bank b = bank4("B3");
        Long examId = createExam("E3");
        // First composition: two sections, three questions.
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S0", null, List.of(
                                new CompositionQuestionRequest(b.singleQ, 0, null),
                                new CompositionQuestionRequest(b.multiQ, 1, null))),
                        new CompositionSectionRequest(1, "S1", null, List.of(
                                new CompositionQuestionRequest(b.tfQ, 0, null))))));
        long draftId = draftId(examId);
        assertThat(sectionRepo.findAllByExamVersionIdOrderByPositionAsc(draftId)).hasSize(2);
        assertThat(questionRepo.findAllByExamVersionId(draftId)).hasSize(3);
        // Second composition: one section, one question -> old graph fully replaced.
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "Only", null, List.of(
                                new CompositionQuestionRequest(b.numQ, 0, null))))));
        assertThat(sectionRepo.findAllByExamVersionIdOrderByPositionAsc(draftId)).hasSize(1);
        assertThat(questionRepo.findAllByExamVersionId(draftId)).hasSize(1);
    }

    // -- Test group: EMPTY sections clears draft --

    @Test
    void emptySectionsClearsDraft() {
        Bank b = bank4("B4");
        Long examId = createExam("E4");
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(
                                new CompositionQuestionRequest(b.singleQ, 0, null))))));
        long draftId = draftId(examId);
        assertThat(questionRepo.findAllByExamVersionId(draftId)).hasSize(1);
        // Clear.
        TeacherExamEditorResponse r = examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of()));
        assertThat(r.currentDraftVersion().sections()).isEmpty();
        assertThat(questionRepo.findAllByExamVersionId(draftId)).isEmpty();
        assertThat(sectionRepo.findAllByExamVersionIdOrderByPositionAsc(draftId)).isEmpty();
    }

    // -- Test group: ROLLBACK (invalid source keeps old graph) --

    @Test
    void invalidSourceRollbackKeepsOldGraph() {
        Bank b = bank4("B5");
        Long examId = createExam("E5");
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(
                                new CompositionQuestionRequest(b.singleQ, 0, null))))));
        long draftId = draftId(examId);
        int beforeSections = sectionRepo.findAllByExamVersionIdOrderByPositionAsc(draftId).size();
        int beforeQuestions = questionRepo.findAllByExamVersionId(draftId).size();
        // Attempt a replace that references a missing source -> must throw and leave old graph intact.
        assertThatThrownBy(() -> examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(
                                new CompositionQuestionRequest(999999L, 0, null)))))))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
        assertThat(sectionRepo.findAllByExamVersionIdOrderByPositionAsc(draftId)).hasSize(beforeSections);
        assertThat(questionRepo.findAllByExamVersionId(draftId)).hasSize(beforeQuestions);
    }

    // -- Test group: duplicate position / source rejected --

    @Test
    void duplicateSectionPositionRejected() {
        Bank b = bank4("B6");
        Long examId = createExam("E6");
        assertThatThrownBy(() -> examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "A", null, List.of(new CompositionQuestionRequest(b.singleQ, 0, null))),
                        new CompositionSectionRequest(0, "B", null, List.of(new CompositionQuestionRequest(b.multiQ, 0, null)))))))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    @Test
    void duplicateQuestionPositionInSectionRejected() {
        Bank b = bank4("B7");
        Long examId = createExam("E7");
        assertThatThrownBy(() -> examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "A", null, List.of(
                                new CompositionQuestionRequest(b.singleQ, 0, null),
                                new CompositionQuestionRequest(b.multiQ, 0, null)))))))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    @Test
    void duplicateSourceAcrossSectionsRejected() {
        Bank b = bank4("B8");
        Long examId = createExam("E8");
        assertThatThrownBy(() -> examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "A", null, List.of(new CompositionQuestionRequest(b.singleQ, 0, null))),
                        new CompositionSectionRequest(1, "B", null, List.of(new CompositionQuestionRequest(b.singleQ, 0, null)))))))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    @Test
    void questionPositionZeroInDifferentSectionsAccepted() {
        Bank b = bank4("B9");
        Long examId = createExam("E9");
        TeacherExamEditorResponse r = examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "A", null, List.of(new CompositionQuestionRequest(b.singleQ, 0, null))),
                        new CompositionSectionRequest(1, "B", null, List.of(new CompositionQuestionRequest(b.multiQ, 0, null))))));
        assertThat(r.currentDraftVersion().sections()).hasSize(2);
    }

    // -- Test group: SOURCE POLICY rejects --

    @Test
    void foreignOwnerBankSameSchoolRejected403() {
        // Second teacher in the SAME school owns a bank+question -> not allowed (strict owner match).
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o2','o2@t','h','O2')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", u2);
        long tp2 = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u2 + "," + schoolId + ",'TC2')");
        long bank2 = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp2 + ",'FB','Foreign')");
        long fq = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank2 + ",'fq','ACTIVE',1," + u2 + ")");
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + fq + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + u2 + ")");
        Long examId = createExam("E10");
        assertThatThrownBy(() -> examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(fq, 0, null)))))))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_ACCESS_DENIED));
    }

    @Test
    void crossSchoolAndWrongSubjectAndInactiveRejected() {
        // Cross-school bank (different school, different owner user).
        long s2 = insert("INSERT INTO schools (code, name) VALUES ('XS','XS')");
        long gl2 = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + s2 + ",'GL','G')");
        long sub2 = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + s2 + "," + gl2 + ",'XS','X')");
        long uCs = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ucs','ucs@t','h','UCS')");
        long tpCs = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + uCs + "," + s2 + ",'TCS')");
        long bankCs = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + s2 + "," + sub2 + "," + tpCs + ",'CSB','Cross')");
        long qCs = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bankCs + ",'csq','ACTIVE',1," + teacherUserId + ")");
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + qCs + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");

        // Wrong-subject bank (same owner + school, different subject).
        long subWs = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + ",(SELECT id FROM grade_levels WHERE school_id=" + schoolId + " LIMIT 1),'WS','WrongSub')");
        long bankWs = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subWs + "," + teacherProfileId + ",'WSB','WrongSub')");
        long qWs = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bankWs + ",'wsq','ACTIVE',1," + teacherUserId + ")");
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + qWs + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");

        // Inactive (ARCHIVED) bank, same owner/school/subject.
        long bankIn = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name, status) VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + ",'INB','Inactive','ARCHIVED')");
        long qIn = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bankIn + ",'inq','ACTIVE',1," + teacherUserId + ")");
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + qIn + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");

        // Inactive (ARCHIVED) question in an active bank.
        long bankOk = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + ",'OKB','Ok')");
        long qArch = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bankOk + ",'arq','ARCHIVED',1," + teacherUserId + ")");
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + qArch + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");

        Long examIdCs = createExam("Ecs");
        Long examIdWs = createExam("Ews");
        Long examIdIn = createExam("Ein");
        Long examIdArch = createExam("Earch");

        // Cross-school -> 403.
        assertThatThrownBy(() -> putOne(examIdCs, qCs)).isInstanceOfSatisfying(ExamException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_ACCESS_DENIED));
        // Wrong-subject -> 403.
        assertThatThrownBy(() -> putOne(examIdWs, qWs)).isInstanceOfSatisfying(ExamException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_ACCESS_DENIED));
        // Inactive bank -> 400.
        assertThatThrownBy(() -> putOne(examIdIn, qIn)).isInstanceOfSatisfying(ExamException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
        // Inactive question -> 400.
        assertThatThrownBy(() -> putOne(examIdArch, qArch)).isInstanceOfSatisfying(ExamException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    @Test
    void missingQuestionRejected400() {
        Long examId = createExam("Emiss");
        assertThatThrownBy(() -> putOne(examId, 999999L)).isInstanceOfSatisfying(ExamException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VALIDATION_ERROR));
    }

    // -- Test group: PIN survives bank version bump --

    @Test
    void pinSurvivesBankVersionBump() {
        Bank b = bank4("Bpin");
        Long examId = createExam("Epin");
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(b.singleQ, 0, null))))));
        long draftId = draftId(examId);
        long pinnedAtPut = questionRepo.findAllByExamVersionId(draftId).get(0).getSourceQuestionVersionId();
        assertThat(pinnedAtPut).isEqualTo(b.singleV);
        // Bank bumps the question to version 2 (new current).
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES ("
                + b.singleQ + ",2,'SINGLE_CHOICE','c2','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        jdbc.update("UPDATE questions SET current_version_number = 2 WHERE id = ?", b.singleQ);
        // DRAFT still pinned at version 1.
        long stillPinned = questionRepo.findAllByExamVersionId(draftId).get(0).getSourceQuestionVersionId();
        assertThat(stillPinned).isEqualTo(b.singleV).isEqualTo(pinnedAtPut);
    }

    // -- Test group: optimistic token --

    @Test
    void expectedVersionMismatchReturnsConcurrentModification() {
        Bank b = bank4("Bcm");
        Long examId = createExam("Ecm");
        assertThatThrownBy(() -> examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(99, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(b.singleQ, 0, null)))))))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_CONCURRENT_MODIFICATION));
    }

    @Test
    void noDraftReturnsVersionNotDraft() {
        Bank b = bank4("Bnd");
        Long examId = createExam("End");
        long draftId = draftId(examId);
        // Remove the DRAFT version row so there is no DRAFT to update.
        jdbc.update("DELETE FROM exam_versions WHERE id = ?", draftId);
        assertThatThrownBy(() -> examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(b.singleQ, 0, null)))))))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VERSION_NOT_DRAFT));
    }

    // -- Test group: foreign owner (mutation) -> 403 --

    @Test
    void foreignOwnerPutReturnsAccessDenied() {
        Bank b = bank4("Bfo");
        Long examId = createExam("Efo");
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o3','o3@t','h','O3')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", other);
        insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + other + "," + schoolId + ",'TC3')");
        assertThatThrownBy(() -> examService.updateDraftComposition(other, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(b.singleQ, 0, null)))))))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_ACCESS_DENIED));
    }

    // -- Test group: numeric preservation --

    @Test
    void numericAnswerKeyPreservedVerbatim() {
        // Build a numeric question with expectedAnswer "02.5" (trailing/leading representation preserved).
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + ",'NB','Num')");
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'nq','ACTIVE',1," + teacherUserId + ")");
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, answer_key, metadata, created_by) VALUES ("
                + q + ",1,'NUMERIC_FILL','n','MEDIUM',1,'{\"expectedAnswer\":\"02.5\",\"requiredInputLength\":4,\"roundingInstruction\":\"2dp\"}'::jsonb,'{}'::jsonb," + teacherUserId + ")");
        Long examId = createExam("Enum");
        TeacherExamEditorResponse r = examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(q, 0, null))))));
        var ak = r.currentDraftVersion().sections().get(0).questions().get(0).answerKey();
        assertThat(ak.path("expectedAnswer").asString()).isEqualTo("02.5"); // unchanged representation
        assertThat(ak.path("requiredInputLength").isNumber()).isTrue();
        assertThat(ak.path("requiredInputLength").asInt()).isEqualTo(4);
        assertThat(ak.path("roundingInstruction").asString()).isEqualTo("2dp");
    }

    // -- Test group: query count (no per-source SELECT growth) --

    @Test
    void updateDraftCompositionSelectCountDoesNotGrowWithSourceCount() {
        // Build a bank with 20 ACTIVE single-choice questions (each version + 4 options).
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + ",'QB','Q')");
        List<Long> qIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q" + i + "','ACTIVE',1," + teacherUserId + ")");
            long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
            for (String k : new String[]{"A", "B", "C", "D"}) {
                insert("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + k + "','opt',false," + (k.charAt(0) - 'A') + ")");
            }
            qIds.add(q);
        }
        Long examId = createExam("Eqc");

        Statistics stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // N=1: one section, one source question. (Fixtures created ABOVE, before stats.clear().)
        stats.clear();
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(qIds.get(0), 0, null))))));
        long executionsN1 = selectExecutionCount(stats);

        // N=20: one section, 20 distinct source questions.
        List<CompositionQuestionRequest> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(new CompositionQuestionRequest(qIds.get(i), i, null));
        }
        stats.clear();
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, twenty))));
        long executionsN20 = selectExecutionCount(stats);

        // Measured REAL SELECT executions (Hibernate Statistics sums executions per query,
        // not distinct query strings — a per-source SELECT N+1 would add ~N executions here).
        System.out.println("F1_QUERY_COUNT SELECT executions N=1=" + executionsN1 + " N=20=" + executionsN20);

        long delta = Math.abs(executionsN20 - executionsN1);
        // Reads are batched (questions/banks/versions/options each = 1 query) => constant w.r.t. N.
        // A per-source SELECT regression (e.g. findById in a loop, or per-question option load)
        // adds ~19 executions for N=20 vs N=1 => exceeds this bound and FAILS the test.
        assertThat(delta).as("SELECT execution delta N=1=%d N=20=%d must be constant (no per-source read)", executionsN1, executionsN20)
                .isLessThanOrEqualTo(4L);
        // Sanity ceiling on REAL SELECT executions for N=20 (executions, not distinct strings).
        assertThat(executionsN20).as("SELECT executions N=20").isLessThan(35L);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void putOne(Long examId, Long sourceQuestionId) {
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(sourceQuestionId, 0, null))))));
    }

    private Long createExam(String code) {
        return examService.createExam(teacherUserId,
                new com.quizopia.backend.exam.dto.CreateExamRequest(subjectId, null, code, "T", null)).id();
    }

    private long draftId(Long examId) {
        return versionRepo.findFirstByExamIdAndStatus(examId, ExamVersionStatus.DRAFT).orElseThrow().getId();
    }

    /**
     * Counts REAL SELECT executions (not distinct query strings). Hibernate
     * {@link Statistics#getQueries()} returns distinct query strings; summing
     * {@code getQueryStatistics(q).getExecutionCount()} per query yields the true
     * execution count, so a per-source N+1 that repeats the same SELECT N times is
     * detected (adds N executions, not 1).
     */
    private long selectExecutionCount(Statistics statistics) {
        return Arrays.stream(statistics.getQueries())
                .filter(Objects::nonNull)
                .filter(query -> query.trim().toLowerCase().startsWith("select"))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
                .sum();
    }

    /** Creates a bank owned by the caller with the 4 MVP question types (ACTIVE, each with a version + options). */
    private Bank bank4(String bankCode) {
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES ("
                + schoolId + "," + subjectId + "," + teacherProfileId + ",'" + bankCode + "','Bank')");
        long singleQ = choiceQuestion(bank, "s", "SINGLE_CHOICE", new boolean[]{true, false, false, false});
        long multiQ = choiceQuestion(bank, "m", "MULTIPLE_CHOICE", new boolean[]{true, true, false, false});
        long tfQ = choiceQuestion(bank, "t", "TRUE_FALSE_MATRIX", new boolean[]{true, false, true, false});
        long numQ = numericQuestion(bank, "n");
        return new Bank(bank, singleQ, versionOf(singleQ), multiQ, versionOf(multiQ), tfQ, versionOf(tfQ), numQ, versionOf(numQ));
    }

    private long choiceQuestion(long bank, String code, String type, boolean[] correct) {
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'" + code + "','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'" + type + "','single','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        String[] keys = type.equals("TRUE_FALSE_MATRIX") ? new String[]{"A", "B", "C", "D"} : new String[]{"A", "B", "C", "D"};
        for (int i = 0; i < keys.length; i++) {
            insert("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + keys[i] + "','opt'," + correct[i] + "," + i + ")");
        }
        return q;
    }

    private long numericQuestion(long bank, String code) {
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'" + code + "','ACTIVE',1," + teacherUserId + ")");
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, answer_key, metadata, created_by) VALUES ("
                + q + ",1,'NUMERIC_FILL','n','MEDIUM',1,'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"2dp\"}'::jsonb,'{}'::jsonb," + teacherUserId + ")");
        return q;
    }

    private long versionOf(long questionId) {
        return jdbc.queryForObject("SELECT id FROM question_versions WHERE question_id = ? ORDER BY version_number DESC LIMIT 1", Long.class, questionId);
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    private record Bank(long bankId, long singleQ, long singleV, long multiQ, long multiV,
                        long tfQ, long tfV, long numQ, long numV) {
    }
}
